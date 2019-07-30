package is.hail.expr.ir

import is.hail.HailSuite
import is.hail.annotations._
import is.hail.asm4s._
import is.hail.expr.types.physical._
import is.hail.expr.types.virtual._
import is.hail.io.CodecSpec
import is.hail.utils._
import org.apache.spark.sql.Row
import org.testng.annotations.Test

class Aggregators2Suite extends HailSuite {

  def assertAggEquals(
    aggSig: AggSignature2,
    initOp: IR,
    seqOps: IndexedSeq[IR],
    expected: Any,
    args: IndexedSeq[(String, (Type, Any))],
    nPartitions: Int): Unit = {
    assert(seqOps.length >= 2 * nPartitions, s"Test aggregators with a larger stream!")

    val argT = PType.canonical(TStruct(args.map { case (n, (typ, _)) => n -> typ }: _*)).asInstanceOf[PStruct]
    val argVs = Row.fromSeq(args.map { case (_, (_, v)) => v })
    val argRef = Ref(genUID(), argT.virtualType)
    val spec = CodecSpec.defaultUncompressed

    val (_, combAndDuplicate) = CompileWithAggregators2[Unit](
      Array.fill(nPartitions)(aggSig),
      Begin(
        Array.tabulate(nPartitions)(i => DeserializeAggs(i, i, spec, Array(aggSig))) ++
          Array.range(1, nPartitions).map(i => CombOp2(0, i, aggSig)) :+
          SerializeAggs(0, 0, spec, Array(aggSig)) :+
          DeserializeAggs(1, 0, spec, Array(aggSig))))

    val (rt: PTuple, resF) = CompileWithAggregators2[Long](
      Array.fill(nPartitions)(aggSig),
      ResultOp2(0, Array(aggSig, aggSig)))
    assert(rt.types(0) == rt.types(1))

    val resultType = rt.types(0)
    assert(resultType.virtualType.typeCheck(expected))

    Region.scoped { region =>
      val argOff = ScalaToRegionValue(region, argT, argVs)
      val serializedParts = seqOps.grouped(math.ceil(seqOps.length / nPartitions.toDouble).toInt).map { seqs =>
        val partitionOp = Begin(
          initOp +: seqs :+ SerializeAggs(0, 0, spec, Array(aggSig)))

        val (_, f) = CompileWithAggregators2[Long, Unit](
          Array(aggSig),
          argRef.name, argRef.pType,
          args.map(_._1).foldLeft[IR](partitionOp) { case (op, name) =>
            Let(name, GetField(argRef, name), op)
          })

        val initAndSeq = f(0, region)
        Region.smallScoped { aggRegion =>
          initAndSeq.newAggState(aggRegion)
          initAndSeq(region, argOff, false)
          initAndSeq.getSerializedAgg(0)
        }
      }

      Region.smallScoped { aggRegion =>
        val combOp = combAndDuplicate(0, region)
        combOp.newAggState(aggRegion)
        serializedParts.zipWithIndex.foreach { case (s, i) =>
          combOp.setSerializedAgg(i, s)
        }
        combOp(region)

        val res = resF(0, region)
        res.setAggState(aggRegion, combOp.getAggOffset())
        val double = SafeRow(rt, region, res(region))
        assert(double.get(0) == double.get(1)) // state does not change through serialization
        assert(double.get(0) == expected)
      }
    }
  }

  def assertAggEquals(
    aggSig: AggSignature2,
    initArgs: IndexedSeq[IR],
    seqArgs: IndexedSeq[IndexedSeq[IR]],
    expected: Any,
    args: IndexedSeq[(String, (Type, Any))] = FastIndexedSeq(),
    nPartitions: Int = 2): Unit =
    assertAggEquals(aggSig,
      InitOp2(0, initArgs, aggSig),
      seqArgs.map(s => SeqOp2(0, s, aggSig)),
      expected, args, nPartitions)

  val t = TStruct("a" -> TString(), "b" -> TInt64())
  val rows = FastIndexedSeq(Row("abcd", 5L), null, Row(null, -2L), Row("abcd", 7L), null, Row("foo", null))
  val arrayType = TArray(t)

  val pnnAggSig = AggSignature2(PrevNonnull(), FastSeq[Type](), FastSeq[Type](t), None)
  val countAggSig = AggSignature2(Count(), FastSeq[Type](), FastSeq[Type](), None)
  val sumAggSig = AggSignature2(Sum(), FastSeq[Type](), FastSeq[Type](TInt64()), None)

  @Test def TestCount() {
    val aggSig = AggSignature2(Count(), FastSeq(), FastSeq(), None)
    val seqOpArgs = Array.fill(rows.length)(FastIndexedSeq[IR]())

    assertAggEquals(aggSig, FastIndexedSeq(), seqOpArgs, expected = rows.length.toLong, args = FastIndexedSeq(("rows", (arrayType, rows))))
  }

  @Test def testSum() {
    val aggSig = AggSignature2(Sum(), FastSeq(), FastSeq(TInt64()), None)
    val seqOpArgs = Array.tabulate(rows.length)(i => FastIndexedSeq[IR](GetField(ArrayRef(Ref("rows", arrayType), i), "b")))

    assertAggEquals(aggSig, FastIndexedSeq(), seqOpArgs, expected = 10L, args = FastIndexedSeq(("rows", (arrayType, rows))))
  }

  @Test def testPrevNonnullStr() {
    val aggSig = AggSignature2(PrevNonnull(), FastSeq(), FastSeq(TString()), None)
    val seqOpArgs = Array.tabulate(rows.length)(i => FastIndexedSeq[IR](GetField(ArrayRef(Ref("rows", arrayType), i), "a")))

    assertAggEquals(aggSig, FastIndexedSeq(), seqOpArgs, expected = rows.last.get(0), args = FastIndexedSeq(("rows", (arrayType, rows))))
  }

  @Test def testPrevNonnull() {
    val aggSig = AggSignature2(PrevNonnull(), FastSeq(), FastSeq(t), None)
    val seqOpArgs = Array.tabulate(rows.length)(i => FastIndexedSeq[IR](ArrayRef(Ref("rows", TArray(t)), i)))

    assertAggEquals(aggSig, FastIndexedSeq(), seqOpArgs, expected = rows.last, args = FastIndexedSeq(("rows", (arrayType, rows))))
  }

  def seqOpOverArray(aggIdx: Int, a: IR, seqOps: IR => IR, lcSig: AggSignature2): IR = {
    val idx = Ref(genUID(), TInt32())
    val elt = Ref(genUID(), coerce[TArray](a.typ).elementType)

    val eltSig = AggSignature2(AggElements(), FastSeq[Type](), FastSeq[Type](TInt32(), TVoid), lcSig.nested)

    Begin(FastIndexedSeq(
      SeqOp2(aggIdx, FastIndexedSeq(ArrayLen(a)), lcSig),
      ArrayFor(ArrayRange(0, ArrayLen(a), 1), idx.name,
        Let(elt.name, ArrayRef(a, idx),
          SeqOp2(aggIdx, FastIndexedSeq(idx, seqOps(elt)), eltSig)))))
  }

  @Test def testArrayElementsAgg() {
    val aggSigs = FastIndexedSeq(pnnAggSig, countAggSig, sumAggSig)
    val lcAggSig = AggSignature2(AggElementsLengthCheck(), FastSeq[Type](TVoid), FastSeq[Type](TInt32()), Some(aggSigs))

    val value = FastIndexedSeq(
      FastIndexedSeq(Row("a", 0L), Row("b", 0L), Row("c", 0L), Row("f", 0L)),
      FastIndexedSeq(Row("a", 1L), null, Row("c", 1L), null),
      FastIndexedSeq(Row("a", 2L), Row("b", 2L), null, Row("f", 2L)),
      FastIndexedSeq(Row("a", 3L), Row("b", 3L), Row("c", 3L), Row("f", 3L)),
      FastIndexedSeq(Row("a", 4L), Row("b", 4L), Row("c", 4L), null),
      FastIndexedSeq(null, null, null, Row("f", 5L)))

    val expected =
      FastIndexedSeq(
        Row(Row("a", 4L), 6L, 10L),
        Row(Row("b", 4L), 6L, 9L),
        Row(Row("c", 4L), 6L, 8L),
        Row(Row("f", 5L), 6L, 10L))

    val init = InitOp2(0, FastIndexedSeq(Begin(FastIndexedSeq[IR](
      InitOp2(0, FastIndexedSeq(), pnnAggSig),
      InitOp2(1, FastIndexedSeq(), countAggSig),
      InitOp2(2, FastIndexedSeq(), sumAggSig)
    ))), lcAggSig)

    val stream = Ref("stream", TArray(arrayType))
    val seq = Array.tabulate(value.length) { i =>
      seqOpOverArray(0, ArrayRef(stream, i), { elt =>
        Begin(FastIndexedSeq(
          SeqOp2(0, FastIndexedSeq(elt), pnnAggSig),
          SeqOp2(1, FastIndexedSeq(), countAggSig),
          SeqOp2(2, FastIndexedSeq(GetField(elt, "b")), sumAggSig)))
      }, lcAggSig)
    }

    assertAggEquals(lcAggSig, init, seq, expected, FastIndexedSeq(("stream", (stream.typ, value))), 2)
  }

  @Test def testNestedArrayElementsAgg() {
    val lcAggSig1 = AggSignature2(AggElementsLengthCheck(),
      FastSeq[Type](TVoid), FastSeq[Type](TInt32()),
      Some(FastIndexedSeq(sumAggSig)))
    val lcAggSig2 = AggSignature2(AggElementsLengthCheck(),
      FastSeq[Type](TVoid), FastSeq[Type](TInt32()),
      Some(FastIndexedSeq(lcAggSig1)))

    val init = InitOp2(0, FastIndexedSeq(Begin(FastIndexedSeq[IR](
      InitOp2(0, FastIndexedSeq(Begin(FastIndexedSeq[IR](
        InitOp2(0, FastIndexedSeq(), sumAggSig)
      ))), lcAggSig1)
    ))), lcAggSig2)

    val stream = Ref("stream", TArray(TArray(TArray(TInt64()))))
    val seq = Array.tabulate(10) { i =>
      seqOpOverArray(0, ArrayRef(stream, i), { array1 =>
        seqOpOverArray(0, array1, { elt =>
          SeqOp2(0, FastIndexedSeq(elt), sumAggSig)
        }, lcAggSig1)
      }, lcAggSig2)
    }

    val expected = FastIndexedSeq(Row(FastIndexedSeq(Row(45L))))

    val args = Array.tabulate(10)(i => FastIndexedSeq(FastIndexedSeq(i.toLong))).toFastIndexedSeq
    assertAggEquals(lcAggSig2, init, seq, expected, FastIndexedSeq(("stream", (stream.typ, args))), 2)
  }
}
