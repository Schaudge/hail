package is.hail.backend.spark

import is.hail.{HailContext, HailFeatureFlags}
import is.hail.annotations._
import is.hail.asm4s._
import is.hail.backend._
import is.hail.expr.{JSONAnnotationImpex, SparkAnnotationImpex, Validate}
import is.hail.expr.ir.{IRParser, _}
import is.hail.expr.ir.IRParser.parseType
import is.hail.expr.ir.analyses.SemanticHash
import is.hail.expr.ir.lowering._
import is.hail.io.{BufferSpec, TypedCodecSpec}
import is.hail.io.fs._
import is.hail.linalg.{BlockMatrix, RowMatrix}
import is.hail.rvd.RVD
import is.hail.stats.LinearMixedModel
import is.hail.types._
import is.hail.types.physical.{PStruct, PTuple}
import is.hail.types.physical.stypes.PTypeReferenceSingleCodeType
import is.hail.types.virtual._
import is.hail.utils._
import is.hail.variant.ReferenceGenome

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.ExecutionException
import scala.reflect.ClassTag
import scala.util.control.NonFatal

import java.io.{Closeable, PrintWriter}

import org.apache.hadoop
import org.apache.hadoop.conf.Configuration
import org.apache.spark._
import org.apache.spark.broadcast.Broadcast
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.{DataFrame, SparkSession}
import org.json4s
import org.json4s.jackson.JsonMethods
import sourcecode.Enclosing

class SparkBroadcastValue[T](bc: Broadcast[T]) extends BroadcastValue[T] with Serializable {
  def value: T = bc.value
}

object SparkTaskContext {
  def get(): SparkTaskContext = taskContext.get

  private[this] val taskContext: ThreadLocal[SparkTaskContext] =
    new ThreadLocal[SparkTaskContext]() {
      override def initialValue(): SparkTaskContext = {
        val sparkTC = TaskContext.get()
        assert(sparkTC != null, "Spark Task Context was null, maybe this ran on the driver?")
        sparkTC.addTaskCompletionListener[Unit]((_: TaskContext) => SparkTaskContext.finish())

        // this must be the only place where SparkTaskContext classes are created
        new SparkTaskContext(sparkTC)
      }
    }

  def finish(): Unit = {
    taskContext.get().close()
    taskContext.remove()
  }
}

class SparkTaskContext private[spark] (ctx: TaskContext) extends HailTaskContext {
  self =>
  override def stageId(): Int = ctx.stageId()
  override def partitionId(): Int = ctx.partitionId()
  override def attemptNumber(): Int = ctx.attemptNumber()
}

object SparkBackend {
  object Flags {
    val MaxStageParallelism = "spark_max_stage_parallelism"
  }

  private var theSparkBackend: SparkBackend = _

  def sparkContext(op: String): SparkContext = HailContext.sparkBackend(op).sc

  def checkSparkCompatibility(jarVersion: String, sparkVersion: String): Unit = {
    def majorMinor(version: String): String = version.split("\\.", 3).take(2).mkString(".")

    if (majorMinor(jarVersion) != majorMinor(sparkVersion))
      fatal(
        s"This Hail JAR was compiled for Spark $jarVersion, cannot run with Spark $sparkVersion.\n" +
          s"  The major and minor versions must agree, though the patch version can differ."
      )
    else if (jarVersion != sparkVersion)
      warn(
        s"This Hail JAR was compiled for Spark $jarVersion, running with Spark $sparkVersion.\n" +
          s"  Compatibility is not guaranteed."
      )
  }

  def createSparkConf(appName: String, master: String, local: String, blockSize: Long)
    : SparkConf = {
    require(blockSize >= 0)
    checkSparkCompatibility(is.hail.HAIL_SPARK_VERSION, org.apache.spark.SPARK_VERSION)

    val conf = new SparkConf().setAppName(appName)

    if (master != null)
      conf.setMaster(master)
    else {
      if (!conf.contains("spark.master"))
        conf.setMaster(local)
    }

    conf.set("spark.logConf", "true")
    conf.set("spark.ui.showConsoleProgress", "false")

    conf.set("spark.kryoserializer.buffer.max", "1g")
    conf.set("spark.driver.maxResultSize", "0")

    conf.set(
      "spark.hadoop.io.compression.codecs",
      "org.apache.hadoop.io.compress.DefaultCodec," +
        "is.hail.io.compress.BGzipCodec," +
        "is.hail.io.compress.BGzipCodecTbi," +
        "org.apache.hadoop.io.compress.GzipCodec",
    )

    conf.set("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
    conf.set("spark.kryo.registrator", "is.hail.kryo.HailKryoRegistrator")

    conf.set(
      "spark.hadoop.mapreduce.input.fileinputformat.split.minsize",
      (blockSize * 1024L * 1024L).toString,
    )

    // load additional Spark properties from HAIL_SPARK_PROPERTIES
    val hailSparkProperties = System.getenv("HAIL_SPARK_PROPERTIES")
    if (hailSparkProperties != null) {
      hailSparkProperties
        .split(",")
        .foreach { p =>
          p.split("=") match {
            case Array(k, v) =>
              log.info(s"set Spark property from HAIL_SPARK_PROPERTIES: $k=$v")
              conf.set(k, v)
            case _ =>
              warn(s"invalid key-value property pair in HAIL_SPARK_PROPERTIES: $p")
          }
        }
    }
    conf
  }

  def configureAndCreateSparkContext(
    appName: String,
    master: String,
    local: String,
    blockSize: Long,
  ): SparkContext =
    new SparkContext(createSparkConf(appName, master, local, blockSize))

  def checkSparkConfiguration(sc: SparkContext): Unit = {
    val conf = sc.getConf

    val problems = new mutable.ArrayBuffer[String]

    val serializer = conf.getOption("spark.serializer")
    val kryoSerializer = "org.apache.spark.serializer.KryoSerializer"
    if (!serializer.contains(kryoSerializer))
      problems += s"Invalid configuration property spark.serializer: required $kryoSerializer.  " +
        s"Found: ${serializer.getOrElse("empty parameter")}."

    if (
      !conf.getOption("spark.kryo.registrator").exists(
        _.split(",").contains("is.hail.kryo.HailKryoRegistrator")
      )
    )
      problems += s"Invalid config parameter: spark.kryo.registrator must include is.hail.kryo.HailKryoRegistrator." +
        s"Found ${conf.getOption("spark.kryo.registrator").getOrElse("empty parameter.")}"

    if (problems.nonEmpty)
      fatal(
        s"""Found problems with SparkContext configuration:
           |  ${problems.mkString("\n  ")}""".stripMargin
      )
  }

  def hailCompressionCodecs: Array[String] = Array(
    "org.apache.hadoop.io.compress.DefaultCodec",
    "is.hail.io.compress.BGzipCodec",
    "is.hail.io.compress.BGzipCodecTbi",
    "org.apache.hadoop.io.compress.GzipCodec",
  )

  /** If a SparkBackend has already been initialized, this function returns it regardless of the
    * parameters with which it was initialized.
    *
    * Otherwise, it initializes and returns a new HailContext.
    */
  def getOrCreate(
    sc: SparkContext = null,
    appName: String = "Hail",
    master: String = null,
    local: String = "local[*]",
    logFile: String = "hail.log",
    quiet: Boolean = false,
    append: Boolean = false,
    skipLoggingConfiguration: Boolean = false,
    minBlockSize: Long = 1L,
    tmpdir: String = "/tmp",
    localTmpdir: String = "file:///tmp",
    gcsRequesterPaysProject: String = null,
    gcsRequesterPaysBuckets: String = null,
  ): SparkBackend = synchronized {
    if (theSparkBackend == null)
      return SparkBackend(sc, appName, master, local, logFile, quiet, append,
        skipLoggingConfiguration,
        minBlockSize, tmpdir, localTmpdir, gcsRequesterPaysProject, gcsRequesterPaysBuckets)

    // there should be only one SparkContext
    assert(sc == null || (sc eq theSparkBackend.sc))

    val initializedMinBlockSize =
      theSparkBackend.sc.getConf.getLong(
        "spark.hadoop.mapreduce.input.fileinputformat.split.minsize",
        0L,
      ) / 1024L / 1024L
    if (minBlockSize != initializedMinBlockSize)
      warn(
        s"Requested minBlockSize $minBlockSize, but already initialized to $initializedMinBlockSize.  Ignoring requested setting."
      )

    if (master != null) {
      val initializedMaster = theSparkBackend.sc.master
      if (master != initializedMaster)
        warn(
          s"Requested master $master, but already initialized to $initializedMaster.  Ignoring requested setting."
        )
    }

    theSparkBackend
  }

  def apply(
    sc: SparkContext = null,
    appName: String = "Hail",
    master: String = null,
    local: String = "local[*]",
    logFile: String = "hail.log",
    quiet: Boolean = false,
    append: Boolean = false,
    skipLoggingConfiguration: Boolean = false,
    minBlockSize: Long = 1L,
    tmpdir: String,
    localTmpdir: String,
    gcsRequesterPaysProject: String = null,
    gcsRequesterPaysBuckets: String = null,
  ): SparkBackend = synchronized {
    require(theSparkBackend == null)

    if (!skipLoggingConfiguration)
      HailContext.configureLogging(logFile, quiet, append)

    var sc1 = sc
    if (sc1 == null)
      sc1 = configureAndCreateSparkContext(appName, master, local, minBlockSize)

    sc1.hadoopConfiguration.set("io.compression.codecs", hailCompressionCodecs.mkString(","))

    checkSparkConfiguration(sc1)

    if (!quiet)
      ProgressBarBuilder.build(sc1)

    sc1.uiWebUrl.foreach(ui => info(s"SparkUI: $ui"))

    theSparkBackend =
      new SparkBackend(tmpdir, localTmpdir, sc1, gcsRequesterPaysProject, gcsRequesterPaysBuckets)
    theSparkBackend.addDefaultReferences()
    theSparkBackend
  }

  def stop(): Unit = synchronized {
    if (theSparkBackend != null) {
      theSparkBackend.sc.stop()
      theSparkBackend = null
      // Hadoop does not honor the hadoop configuration as a component of the cache key for file
      // systems, so we blow away the cache so that a new configuration can successfully take
      // effect.
      // https://github.com/hail-is/hail/pull/12133#issuecomment-1241322443
      hadoop.fs.FileSystem.closeAll()
    }
  }
}

// This indicates a narrow (non-shuffle) dependency on _rdd. It works since narrow dependency `getParents`
// is only used to compute preferred locations, which is something we don't need to worry about
class AnonymousDependency[T](val _rdd: RDD[T]) extends NarrowDependency[T](_rdd) {
  override def getParents(partitionId: Int): Seq[Int] = Seq.empty
}

class SparkBackend(
  val tmpdir: String,
  val localTmpdir: String,
  val sc: SparkContext,
  gcsRequesterPaysProject: String,
  gcsRequesterPaysBuckets: String,
) extends Backend with Closeable with BackendWithCodeCache {
  assert(gcsRequesterPaysProject != null || gcsRequesterPaysBuckets == null)
  lazy val sparkSession: SparkSession = SparkSession.builder().config(sc.getConf).getOrCreate()

  private[this] val theHailClassLoader: HailClassLoader =
    new HailClassLoader(getClass().getClassLoader())

  override def canExecuteParallelTasksOnDriver: Boolean = false

  val fs: HadoopFS = {
    val conf = new Configuration(sc.hadoopConfiguration)
    if (gcsRequesterPaysProject != null) {
      if (gcsRequesterPaysBuckets == null) {
        conf.set("fs.gs.requester.pays.mode", "AUTO")
        conf.set("fs.gs.requester.pays.project.id", gcsRequesterPaysProject)
      } else {
        conf.set("fs.gs.requester.pays.mode", "CUSTOM")
        conf.set("fs.gs.requester.pays.project.id", gcsRequesterPaysProject)
        conf.set("fs.gs.requester.pays.buckets", gcsRequesterPaysBuckets)
      }
    }
    new HadoopFS(new SerializableHadoopConfiguration(conf))
  }

  private[this] val longLifeTempFileManager: TempFileManager = new OwningTempFileManager(fs)

  val bmCache: SparkBlockMatrixCache = SparkBlockMatrixCache()

  private[this] val flags = HailFeatureFlags.fromEnv()

  def getFlag(name: String): String = flags.get(name)

  def setFlag(name: String, value: String) = flags.set(name, value)

  val availableFlags: java.util.ArrayList[String] = flags.available

  def persist(backendContext: BackendContext, id: String, value: BlockMatrix, storageLevel: String)
    : Unit = bmCache.persistBlockMatrix(id, value, storageLevel)

  def unpersist(backendContext: BackendContext, id: String): Unit = unpersist(id)

  def getPersistedBlockMatrix(backendContext: BackendContext, id: String): BlockMatrix =
    bmCache.getPersistedBlockMatrix(id)

  def getPersistedBlockMatrixType(backendContext: BackendContext, id: String): BlockMatrixType =
    bmCache.getPersistedBlockMatrixType(id)

  def unpersist(id: String): Unit = bmCache.unpersistBlockMatrix(id)

  def createExecuteContextForTests(
    timer: ExecutionTimer,
    region: Region,
    selfContainedExecution: Boolean = true,
  ): ExecuteContext =
    new ExecuteContext(
      tmpdir,
      localTmpdir,
      this,
      fs,
      region,
      timer,
      if (selfContainedExecution) null else NonOwningTempFileManager(longLifeTempFileManager),
      theHailClassLoader,
      flags,
      new BackendContext {
        override val executionCache: ExecutionCache =
          ExecutionCache.forTesting
      },
      new IrMetadata(),
    )

  def withExecuteContext[T](
    selfContainedExecution: Boolean = true
  )(
    f: ExecuteContext => T
  )(implicit E: Enclosing
  ): T =
    withExecuteContext(
      if (selfContainedExecution) null else NonOwningTempFileManager(longLifeTempFileManager)
    )(f)

  override def withExecuteContext[T](f: ExecuteContext => T)(implicit E: Enclosing): T =
    withExecuteContext(null.asInstanceOf[TempFileManager])(f)

  def withExecuteContext[T](
    tmpFileManager: TempFileManager
  )(
    f: ExecuteContext => T
  )(implicit E: Enclosing
  ): T =
    ExecutionTimer.logTime { timer =>
      ExecuteContext.scoped(
        tmpdir,
        localTmpdir,
        this,
        fs,
        timer,
        tmpFileManager,
        theHailClassLoader,
        flags,
        new BackendContext {
          override val executionCache: ExecutionCache =
            ExecutionCache.fromFlags(flags, fs, tmpdir)
        },
        new IrMetadata(),
      )(f)
    }

  def broadcast[T: ClassTag](value: T): BroadcastValue[T] =
    new SparkBroadcastValue[T](sc.broadcast(value))

  override def parallelizeAndComputeWithIndex(
    backendContext: BackendContext,
    fs: FS,
    contexts: IndexedSeq[Array[Byte]],
    stageIdentifier: String,
    dependency: Option[TableStageDependency],
    partitions: Option[IndexedSeq[Int]],
  )(
    f: (Array[Byte], HailTaskContext, HailClassLoader, FS) => Array[Byte]
  ): (Option[Throwable], IndexedSeq[(Array[Byte], Int)]) = {
    val sparkDeps =
      for {
        rvdDep <- dependency.toIndexedSeq
        dep <- rvdDep.deps
      } yield new AnonymousDependency(dep.asInstanceOf[RVDDependency].rvd.crdd.rdd)

    val rdd =
      new RDD[Array[Byte]](sc, sparkDeps) {

        case class RDDPartition(data: Array[Byte], override val index: Int) extends Partition

        override protected val getPartitions: Array[Partition] =
          Array.tabulate(contexts.length)(index => RDDPartition(contexts(index), index))

        override def compute(partition: Partition, context: TaskContext): Iterator[Array[Byte]] = {
          val sp = partition.asInstanceOf[RDDPartition]
          val fs = new HadoopFS(null)
          Iterator.single(f(sp.data, SparkTaskContext.get(), theHailClassLoaderForSparkWorkers, fs))
        }
      }

    val chunkSize = getFlag(SparkBackend.Flags.MaxStageParallelism).toInt
    val partsToRun = partitions.getOrElse(contexts.indices)
    val buffer = new ArrayBuffer[(Array[Byte], Int)](partsToRun.length)
    var failure: Option[Throwable] = None

    try {
      for (subparts <- partsToRun.grouped(chunkSize)) {
        sc.runJob(
          rdd,
          (_: TaskContext, it: Iterator[Array[Byte]]) => it.next(),
          subparts,
          (idx, result: Array[Byte]) => buffer += result -> subparts(idx),
        )
      }
    } catch {
      case e: ExecutionException => failure = failure.orElse(Some(e.getCause))
      case NonFatal(t) => failure = failure.orElse(Some(t))
    }

    (failure, buffer.sortBy(_._2))
  }

  def defaultParallelism: Int = sc.defaultParallelism

  override def asSpark(op: String): SparkBackend = this

  def close(): Unit = {
    SparkBackend.stop()
    longLifeTempFileManager.close()
  }

  def startProgressBar(): Unit =
    ProgressBarBuilder.build(sc)

  def jvmLowerAndExecute(
    ctx: ExecuteContext,
    ir0: IR,
    optimize: Boolean,
    lowerTable: Boolean,
    lowerBM: Boolean,
    print: Option[PrintWriter] = None,
  ): Any =
    _jvmLowerAndExecute(ctx, ir0, optimize, lowerTable, lowerBM, print) match {
      case Left(x) => x
      case Right((pt, off)) => SafeRow(pt, off).get(0)
    }

  private[this] def _jvmLowerAndExecute(
    ctx: ExecuteContext,
    ir0: IR,
    optimize: Boolean,
    lowerTable: Boolean,
    lowerBM: Boolean,
    print: Option[PrintWriter] = None,
  ): Either[Unit, (PTuple, Long)] =
    ctx.time {
      val typesToLower: DArrayLowering.Type = (lowerTable, lowerBM) match {
        case (true, true) => DArrayLowering.All
        case (true, false) => DArrayLowering.TableOnly
        case (false, true) => DArrayLowering.BMOnly
        case (false, false) => throw new LowererUnsupportedOperation("no lowering enabled")
      }
      val ir =
        LoweringPipeline.darrayLowerer(optimize)(typesToLower).apply(ctx, ir0).asInstanceOf[IR]

      if (!Compilable(ir))
        throw new LowererUnsupportedOperation(s"lowered to uncompilable IR: ${Pretty(ctx, ir)}")

      ir.typ match {
        case TVoid =>
          val (_, f) = Compile[AsmFunction1RegionUnit](
            ctx,
            FastSeq(),
            FastSeq(classInfo[Region]),
            UnitInfo,
            ir,
            print = print,
          )

          Left(ctx.scopedExecution((hcl, fs, htc, r) => f(hcl, fs, htc, r)(r)))
        case _ =>
          val (Some(PTypeReferenceSingleCodeType(pt: PTuple)), f) =
            Compile[AsmFunction1RegionLong](
              ctx,
              FastSeq(),
              FastSeq(classInfo[Region]),
              LongInfo,
              MakeTuple.ordered(FastSeq(ir)),
              print = print,
            )

          Right((pt, ctx.scopedExecution((hcl, fs, htc, r) => f(hcl, fs, htc, r)(r))))
      }
    }

  override def execute(ctx: ExecuteContext, ir: IR): Either[Unit, (PTuple, Long)] =
    ctx.time {
      TypeCheck(ctx, ir)
      Validate(ir)
      ctx.irMetadata.semhash = SemanticHash(ctx)(ir)
      try {
        val lowerTable = getFlag("lower") != null
        val lowerBM = getFlag("lower_bm") != null
        _jvmLowerAndExecute(ctx, ir, optimize = true, lowerTable, lowerBM)
      } catch {
        case e: LowererUnsupportedOperation if getFlag("lower_only") != null => throw e
        case _: LowererUnsupportedOperation =>
          CompileAndEvaluate._apply(ctx, ir, optimize = true)
      }
    }

  def executeLiteral(irStr: String): Int =
    withExecuteContext { ctx =>
      val ir = IRParser.parse_value_ir(irStr, IRParserEnvironment(ctx, persistedIR.toMap))
      assert(ir.typ.isRealizable)
      execute(ctx, ir) match {
        case Left(_) => throw new HailException("Can't create literal")
        case Right((pt, addr)) =>
          val field = GetFieldByIdx(EncodedLiteral.fromPTypeAndAddress(pt, addr, ctx), 0)
          addJavaIR(field)
      }
    }

  def pyFromDF(df: DataFrame, jKey: java.util.List[String]): (Int, String) = {
    val key = jKey.asScala.toArray.toFastSeq
    val signature =
      SparkAnnotationImpex.importType(df.schema).setRequired(true).asInstanceOf[PStruct]
    withExecuteContext(selfContainedExecution = false) { ctx =>
      val tir = TableLiteral(
        TableValue(
          ctx,
          signature.virtualType.asInstanceOf[TStruct],
          key,
          df.rdd,
          Some(signature),
        ),
        ctx.theHailClassLoader,
      )
      val id = addJavaIR(tir)
      (id, JsonMethods.compact(tir.typ.toJSON))
    }
  }

  def pyToDF(s: String): DataFrame =
    withExecuteContext(selfContainedExecution = false) { ctx =>
      val tir = IRParser.parse_table_ir(s, IRParserEnvironment(ctx, irMap = persistedIR.toMap))
      Interpret(tir, ctx).toDF()
    }

  def pyReadMultipleMatrixTables(jsonQuery: String): java.util.List[MatrixIR] = {
    log.info("pyReadMultipleMatrixTables: got query")
    val kvs = JsonMethods.parse(jsonQuery) match {
      case json4s.JObject(values) => values.toMap
    }

    val paths = kvs("paths").asInstanceOf[json4s.JArray].arr.toArray.map { case json4s.JString(s) =>
      s
    }

    val intervalPointType = parseType(kvs("intervalPointType").asInstanceOf[json4s.JString].s)
    val intervalObjects =
      JSONAnnotationImpex.importAnnotation(kvs("intervals"), TArray(TInterval(intervalPointType)))
        .asInstanceOf[IndexedSeq[Interval]]

    val opts = NativeReaderOptions(intervalObjects, intervalPointType, filterIntervals = false)
    val matrixReaders: IndexedSeq[MatrixIR] = paths.map { p =>
      log.info(s"creating MatrixRead node for $p")
      val mnr = MatrixNativeReader(fs, p, Some(opts))
      MatrixRead(mnr.fullMatrixTypeWithoutUIDs, false, false, mnr): MatrixIR
    }
    log.info("pyReadMultipleMatrixTables: returning N matrix tables")
    matrixReaders.asJava
  }

  def pyAddReference(jsonConfig: String): Unit = addReference(ReferenceGenome.fromJSON(jsonConfig))
  def pyRemoveReference(name: String): Unit = removeReference(name)

  def pyAddLiftover(name: String, chainFile: String, destRGName: String): Unit =
    withExecuteContext(ctx => references(name).addLiftover(ctx, chainFile, destRGName))

  def pyRemoveLiftover(name: String, destRGName: String) =
    references(name).removeLiftover(destRGName)

  def pyFromFASTAFile(
    name: String,
    fastaFile: String,
    indexFile: String,
    xContigs: java.util.List[String],
    yContigs: java.util.List[String],
    mtContigs: java.util.List[String],
    parInput: java.util.List[String],
  ): String =
    withExecuteContext { ctx =>
      val rg = ReferenceGenome.fromFASTAFile(
        ctx,
        name,
        fastaFile,
        indexFile,
        xContigs.asScala.toArray,
        yContigs.asScala.toArray,
        mtContigs.asScala.toArray,
        parInput.asScala.toArray,
      )
      rg.toJSONString
    }

  def pyAddSequence(name: String, fastaFile: String, indexFile: String): Unit =
    withExecuteContext(ctx => references(name).addSequence(ctx, fastaFile, indexFile))

  def pyRemoveSequence(name: String) = references(name).removeSequence()

  def pyExportBlockMatrix(
    pathIn: String,
    pathOut: String,
    delimiter: String,
    header: String,
    addIndex: Boolean,
    exportType: String,
    partitionSize: java.lang.Integer,
    entries: String,
  ): Unit =
    withExecuteContext { ctx =>
      val rm = RowMatrix.readBlockMatrix(fs, pathIn, partitionSize)
      entries match {
        case "full" =>
          rm.export(ctx, pathOut, delimiter, Option(header), addIndex, exportType)
        case "lower" =>
          rm.exportLowerTriangle(ctx, pathOut, delimiter, Option(header), addIndex, exportType)
        case "strict_lower" =>
          rm.exportStrictLowerTriangle(
            ctx,
            pathOut,
            delimiter,
            Option(header),
            addIndex,
            exportType,
          )
        case "upper" =>
          rm.exportUpperTriangle(ctx, pathOut, delimiter, Option(header), addIndex, exportType)
        case "strict_upper" =>
          rm.exportStrictUpperTriangle(
            ctx,
            pathOut,
            delimiter,
            Option(header),
            addIndex,
            exportType,
          )
      }
    }

  def pyFitLinearMixedModel(lmm: LinearMixedModel, pa_t: RowMatrix, a_t: RowMatrix): TableIR =
    withExecuteContext(selfContainedExecution = false)(ctx => lmm.fit(ctx, pa_t, Option(a_t)))

  def parse_value_ir(s: String, refMap: java.util.Map[String, String]): IR =
    withExecuteContext { ctx =>
      IRParser.parse_value_ir(
        s,
        IRParserEnvironment(ctx, irMap = persistedIR.toMap),
        BindingEnv.eval(refMap.asScala.toMap.map { case (n, t) =>
          Name(n) -> IRParser.parseType(t)
        }.toSeq: _*),
      )
    }

  def parse_table_ir(s: String): TableIR =
    withExecuteContext(selfContainedExecution = false) { ctx =>
      IRParser.parse_table_ir(s, IRParserEnvironment(ctx, irMap = persistedIR.toMap))
    }

  def parse_matrix_ir(s: String): MatrixIR =
    withExecuteContext(selfContainedExecution = false) { ctx =>
      IRParser.parse_matrix_ir(s, IRParserEnvironment(ctx, irMap = persistedIR.toMap))
    }

  def parse_blockmatrix_ir(s: String): BlockMatrixIR =
    withExecuteContext(selfContainedExecution = false) { ctx =>
      IRParser.parse_blockmatrix_ir(s, IRParserEnvironment(ctx, irMap = persistedIR.toMap))
    }

  override def lowerDistributedSort(
    ctx: ExecuteContext,
    stage: TableStage,
    sortFields: IndexedSeq[SortField],
    rt: RTable,
    nPartitions: Option[Int],
  ): TableReader = {
    if (getFlag("use_new_shuffle") != null)
      return LowerDistributedSort.distributedSort(ctx, stage, sortFields, rt)

    val (globals, rvd) = TableStageToRVD(ctx, stage)
    val globalsLit = globals.toEncodedLiteral(ctx.theHailClassLoader)

    if (sortFields.forall(_.sortOrder == Ascending)) {
      return RVDTableReader(rvd.changeKey(ctx, sortFields.map(_.field)), globalsLit, rt)
    }

    val rowType = rvd.rowType
    val sortColIndexOrd = sortFields.map { case SortField(n, so) =>
      val i = rowType.fieldIdx(n)
      val f = rowType.fields(i)
      val fo = f.typ.ordering(ctx.stateManager)
      if (so == Ascending) fo else fo.reverse
    }.toArray

    val ord: Ordering[Annotation] = ExtendedOrdering.rowOrdering(sortColIndexOrd).toOrdering

    val act = implicitly[ClassTag[Annotation]]

    val codec = TypedCodecSpec(ctx, rvd.rowPType, BufferSpec.wireSpec)
    val rdd = rvd.keyedEncodedRDD(ctx, codec, sortFields.map(_.field)).sortBy(
      _._1,
      numPartitions = nPartitions.getOrElse(rvd.getNumPartitions),
    )(ord, act)
    val (rowPType: PStruct, orderedCRDD) = codec.decodeRDD(ctx, rowType, rdd.map(_._2))
    RVDTableReader(RVD.unkeyed(rowPType, orderedCRDD), globalsLit, rt)
  }

  def tableToTableStage(ctx: ExecuteContext, inputIR: TableIR, analyses: LoweringAnalyses)
    : TableStage = {
    CanLowerEfficiently(ctx, inputIR) match {
      case Some(failReason) =>
        log.info(s"SparkBackend: could not lower IR to table stage: $failReason")
        inputIR.analyzeAndExecute(ctx).asTableStage(ctx)
      case None =>
        LowerTableIR.applyTable(inputIR, DArrayLowering.All, ctx, analyses)
    }
  }
}
