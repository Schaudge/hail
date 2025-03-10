package is.hail.expr.ir

import is.hail.HailContext
import is.hail.backend.ExecuteContext
import is.hail.expr.{JSONAnnotationImpex, Nat, ParserUtils}
import is.hail.expr.ir.agg._
import is.hail.expr.ir.defs._
import is.hail.expr.ir.functions.RelationalFunctions
import is.hail.io.{BufferSpec, TypedCodecSpec}
import is.hail.rvd.{RVDPartitioner, RVDType}
import is.hail.types.{tcoerce, VirtualTypeWithReq}
import is.hail.types.encoded.EType
import is.hail.types.physical._
import is.hail.types.virtual._
import is.hail.utils._
import is.hail.utils.StackSafe._
import is.hail.utils.StringEscapeUtils._

import scala.collection.mutable
import scala.reflect.ClassTag
import scala.util.parsing.combinator.JavaTokenParsers
import scala.util.parsing.input.Positional

import java.util.Base64

import org.apache.spark.sql.Row
import org.json4s.{Formats, JObject}
import org.json4s.jackson.{JsonMethods, Serialization}

abstract class Token extends Positional {
  def value: Any

  def getName: String
}

final case class IdentifierToken(value: String) extends Token {
  def getName: String = "identifier"
}

final case class StringToken(value: String) extends Token {
  def getName: String = "string"
}

final case class IntegerToken(value: Long) extends Token {
  def getName: String = "integer"
}

final case class FloatToken(value: Double) extends Token {
  def getName: String = "float"
}

final case class PunctuationToken(value: String) extends Token {
  def getName: String = "punctuation"
}

object IRLexer extends JavaTokenParsers {
  val token: Parser[Token] =
    identifier ^^ { id => IdentifierToken(id) } |
      float64_literal ^^ { d => FloatToken(d) } |
      int64_literal ^^ { l => IntegerToken(l) } |
      string_literal ^^ { s => StringToken(s) } |
      "[()\\[\\]{}<>,:+@=]".r ^^ { p => PunctuationToken(p) }

  val lexer: Parser[Array[Token]] = rep(positioned(token)) ^^ { l => l.toArray }

  def quotedLiteral(delim: Char, what: String): Parser[String] =
    new Parser[String] {
      def apply(in: Input): ParseResult[String] = {
        var r = in

        val source = in.source
        val offset = in.offset
        val start = handleWhiteSpace(source, offset)
        r = r.drop(start - offset)

        if (r.atEnd || r.first != delim)
          return Failure(s"consumed $what", r)
        r = r.rest

        val sb = new StringBuilder()

        val escapeChars = "\\bfnrtu'\"`".toSet
        var continue = true
        while (continue) {
          if (r.atEnd)
            return Failure(s"unterminated $what", r)
          val c = r.first
          r = r.rest
          if (c == delim)
            continue = false
          else {
            sb += c
            if (c == '\\') {
              if (r.atEnd)
                return Failure(s"unterminated $what", r)
              val d = r.first
              if (!escapeChars.contains(d))
                return Failure(s"invalid escape character in $what", r)
              sb += d
              r = r.rest
            }
          }
        }
        Success(unescapeString(sb.result()), r)
      }
    }

  override def stringLiteral: Parser[String] =
    quotedLiteral('"', "string literal") | quotedLiteral('\'', "string literal")

  def backtickLiteral: Parser[String] = quotedLiteral('`', "backtick identifier")

  def identifier = backtickLiteral | ident

  def string_literal: Parser[String] = stringLiteral

  def int64_literal: Parser[Long] = wholeNumber.map(_.toLong)

  def float64_literal: Parser[Double] =
    "-inf" ^^ { _ => Double.NegativeInfinity } | // inf, neginf, and nan are parsed as identifiers
      """[+-]?\d+(\.\d+)?[eE][+-]?\d+""".r ^^ { _.toDouble } |
      """[+-]?\d*\.\d+""".r ^^ { _.toDouble }

  def parse(code: String): Array[Token] =
    parseAll(lexer, code) match {
      case Success(result, _) => result
      case NoSuccess(msg, next) => ParserUtils.error(next.pos, msg)
    }
}

case class IRParserEnvironment(
  ctx: ExecuteContext,
  irMap: Map[Int, BaseIR] = Map.empty,
)

object IRParser {
  def error(t: Token, msg: String): Nothing = ParserUtils.error(t.pos, msg)

  def deserialize[T](str: String)(implicit formats: Formats, mf: Manifest[T]): T = {
    try
      Serialization.read[T](str)
    catch {
      case e: org.json4s.MappingException =>
        throw new RuntimeException(s"Couldn't deserialize $str", e)
    }

  }

  def consumeToken(it: TokenIterator): Token = {
    if (!it.hasNext)
      fatal("No more tokens to consume.")
    it.next()
  }

  def punctuation(it: TokenIterator, symbol: String): String =
    consumeToken(it) match {
      case x: PunctuationToken if x.value == symbol => x.value
      case x: Token =>
        error(x, s"Expected punctuation '$symbol' but found ${x.getName} '${x.value}'.")
    }

  def identifier(it: TokenIterator): String =
    consumeToken(it) match {
      case x: IdentifierToken => x.value
      case x: Token => error(x, s"Expected identifier but found ${x.getName} '${x.value}'.")
    }

  def identifier(it: TokenIterator, expectedId: String): String =
    consumeToken(it) match {
      case x: IdentifierToken if x.value == expectedId => x.value
      case x: Token =>
        error(x, s"Expected identifier '$expectedId' but found ${x.getName} '${x.value}'.")
    }

  def identifiers(it: TokenIterator): Array[String] =
    base_seq_parser(identifier)(it)

  def name(it: TokenIterator): Name = Name(identifier(it))

  def names(it: TokenIterator): Array[Name] =
    base_seq_parser(name)(it)

  def boolean_literal(it: TokenIterator): Boolean =
    consumeToken(it) match {
      case IdentifierToken("True") => true
      case IdentifierToken("False") => false
      case x: Token => error(x, s"Expected boolean but found ${x.getName} '${x.value}'.")
    }

  def int32_literal(it: TokenIterator): Int = {
    consumeToken(it) match {
      case x: IntegerToken =>
        if (x.value >= Int.MinValue && x.value <= Int.MaxValue)
          x.value.toInt
        else
          error(x, s"Found integer '${x.value}' that is outside the numeric range for int32.")
      case x: Token => error(x, s"Expected integer but found ${x.getName} '${x.value}'.")
    }
  }

  def int64_literal(it: TokenIterator): Long =
    consumeToken(it) match {
      case x: IntegerToken => x.value
      case x: Token => error(x, s"Expected integer but found ${x.getName} '${x.value}'.")
    }

  def float32_literal(it: TokenIterator): Float = {
    consumeToken(it) match {
      case x: FloatToken =>
        if (x.value >= Float.MinValue && x.value <= Float.MaxValue)
          x.value.toFloat
        else
          error(x, s"Found float '${x.value}' that is outside the numeric range for float32.")
      case x: IntegerToken => x.value.toFloat
      case x: IdentifierToken => x.value match {
          case "nan" => Float.NaN
          case "inf" => Float.PositiveInfinity
          case "neginf" => Float.NegativeInfinity
          case _ => error(x, s"Expected float but found ${x.getName} '${x.value}'.")
        }
      case x: Token => error(x, s"Expected float but found ${x.getName} '${x.value}'.")
    }
  }

  def float64_literal(it: TokenIterator): Double = {
    consumeToken(it) match {
      case x: FloatToken => x.value
      case x: IntegerToken => x.value.toDouble
      case x: IdentifierToken => x.value match {
          case "nan" => Double.NaN
          case "inf" => Double.PositiveInfinity
          case "neginf" => Double.NegativeInfinity
          case _ => error(x, s"Expected float but found ${x.getName} '${x.value}'.")
        }
      case x: Token => error(x, s"Expected float but found ${x.getName} '${x.value}'.")
    }
  }

  def string_literal(it: TokenIterator): String =
    consumeToken(it) match {
      case x: StringToken => x.value
      case x: Token => error(x, s"Expected string but found ${x.getName} '${x.value}'.")
    }

  def partitioner_literal(env: IRParserEnvironment)(it: TokenIterator): RVDPartitioner = {
    identifier(it, "Partitioner")
    val keyType = type_expr(it).asInstanceOf[TStruct]
    val vJSON = JsonMethods.parse(string_literal(it))
    val rangeBounds = JSONAnnotationImpex.importAnnotation(vJSON, TArray(TInterval(keyType)))
    new RVDPartitioner(
      env.ctx.stateManager,
      keyType,
      rangeBounds.asInstanceOf[mutable.IndexedSeq[Interval]],
    )
  }

  def literals[T](
    literalIdentifier: TokenIterator => T
  )(
    it: TokenIterator
  )(implicit tct: ClassTag[T]
  ): Array[T] =
    base_seq_parser(literalIdentifier)(it)

  def between[A](
    open: TokenIterator => Any,
    close: TokenIterator => Any,
    f: TokenIterator => A,
  )(
    it: TokenIterator
  ): A = {
    open(it)
    val a = f(it)
    close(it)
    a
  }

  def string_literals: TokenIterator => Array[String] = literals(string_literal)
  def int32_literals: TokenIterator => Array[Int] = literals(int32_literal)
  def int64_literals: TokenIterator => Array[Long] = literals(int64_literal)

  def opt[T](it: TokenIterator, f: (TokenIterator) => T)(implicit tct: ClassTag[T]): Option[T] = {
    it.head match {
      case x: IdentifierToken if x.value == "None" =>
        consumeToken(it)
        None
      case _ =>
        Some(f(it))
    }
  }

  def repsepUntil[T](
    it: TokenIterator,
    f: (TokenIterator) => T,
    sep: Token,
    end: Token,
  )(implicit tct: ClassTag[T]
  ): Array[T] = {
    val xs = new mutable.ArrayBuffer[T]()
    while (it.hasNext && it.head != end) {
      xs += f(it)
      if (it.head == sep)
        consumeToken(it)
    }
    xs.toArray
  }

  def repUntil[T](
    it: TokenIterator,
    f: (TokenIterator) => StackFrame[T],
    end: Token,
  )(implicit tct: ClassTag[T]
  ): StackFrame[Array[T]] = {
    val xs = new mutable.ArrayBuffer[T]()
    var cont: T => StackFrame[Array[T]] = null
    def loop(): StackFrame[Array[T]] =
      if (it.hasNext && it.head != end) {
        f(it).flatMap(cont)
      } else {
        done(xs.toArray)
      }
    cont = { t =>
      xs += t
      loop()
    }
    loop()
  }

  def repUntilNonStackSafe[T](
    it: TokenIterator,
    f: (TokenIterator) => T,
    end: Token,
  )(implicit tct: ClassTag[T]
  ): Array[T] = {
    val xs = new mutable.ArrayBuffer[T]()
    while (it.hasNext && it.head != end)
      xs += f(it)
    xs.toArray
  }

  def base_seq_parser[T: ClassTag](f: TokenIterator => T)(it: TokenIterator): Array[T] = {
    punctuation(it, "(")
    val r = repUntilNonStackSafe(it, f, PunctuationToken(")"))
    punctuation(it, ")")
    r
  }

  def decorator(it: TokenIterator): (String, String) = {
    punctuation(it, "@")
    val name = identifier(it)
    punctuation(it, "=")
    val desc = string_literal(it)
    (name, desc)
  }

  def ptuple_subset_field(it: TokenIterator): (Int, PType) = {
    val i = int32_literal(it)
    punctuation(it, ":")
    val t = ptype_expr(it)
    i -> t
  }

  def tuple_subset_field(it: TokenIterator): (Int, Type) = {
    val i = int32_literal(it)
    punctuation(it, ":")
    val t = type_expr(it)
    i -> t
  }

  def struct_field[T](f: TokenIterator => T)(it: TokenIterator): (String, T) = {
    val name = identifier(it)
    punctuation(it, ":")
    val typ = f(it)
    while (it.hasNext && it.head == PunctuationToken("@"))
      decorator(it)
    (name, typ)
  }

  def ptype_field(it: TokenIterator): (String, PType) =
    struct_field(ptype_expr)(it)

  def type_field(it: TokenIterator): (String, Type) =
    struct_field(type_expr)(it)

  def vtwr_expr(it: TokenIterator): VirtualTypeWithReq = {
    val pt = ptype_expr(it)
    VirtualTypeWithReq(pt)
  }

  def ptype_expr(it: TokenIterator): PType = {
    val req = it.head match {
      case x: PunctuationToken if x.value == "+" =>
        consumeToken(it)
        true
      case _ => false
    }

    val typ = identifier(it) match {
      case "PCInterval" =>
        punctuation(it, "[")
        val pointType = ptype_expr(it)
        punctuation(it, "]")
        PCanonicalInterval(pointType, req)
      case "PBoolean" => PBoolean(req)
      case "PInt32" => PInt32(req)
      case "PInt64" => PInt64(req)
      case "PFloat32" => PFloat32(req)
      case "PFloat64" => PFloat64(req)
      case "PCBinary" => PCanonicalBinary(req)
      case "PCString" => PCanonicalString(req)
      case "PCLocus" =>
        punctuation(it, "(")
        val rg = identifier(it)
        punctuation(it, ")")
        PCanonicalLocus(rg, req)
      case "PCCall" => PCanonicalCall(req)
      case "PCArray" =>
        punctuation(it, "[")
        val elementType = ptype_expr(it)
        punctuation(it, "]")
        PCanonicalArray(elementType, req)
      case "PCNDArray" =>
        punctuation(it, "[")
        val elementType = ptype_expr(it)
        punctuation(it, ",")
        val nDims = int32_literal(it)
        punctuation(it, "]")
        PCanonicalNDArray(elementType, nDims, req)
      case "PCSet" =>
        punctuation(it, "[")
        val elementType = ptype_expr(it)
        punctuation(it, "]")
        PCanonicalSet(elementType, req)
      case "PCDict" =>
        punctuation(it, "[")
        val keyType = ptype_expr(it)
        punctuation(it, ",")
        val valueType = ptype_expr(it)
        punctuation(it, "]")
        PCanonicalDict(keyType, valueType, req)
      case "PCTuple" =>
        punctuation(it, "[")
        val fields =
          repsepUntil(it, ptuple_subset_field, PunctuationToken(","), PunctuationToken("]"))
        punctuation(it, "]")
        PCanonicalTuple(fields.map { case (idx, t) => PTupleField(idx, t) }, req)
      case "PCStruct" =>
        punctuation(it, "{")
        val args = repsepUntil(it, ptype_field, PunctuationToken(","), PunctuationToken("}"))
        punctuation(it, "}")
        val fields = args.zipWithIndex.map { case ((id, t), i) => PField(id, t, i) }
        PCanonicalStruct(fields, req)
      case "PSubsetStruct" =>
        punctuation(it, "{")
        val parent = ptype_expr(it).asInstanceOf[PStruct]
        punctuation(it, "{")
        val args = repsepUntil(it, identifier, PunctuationToken(","), PunctuationToken("}"))
        punctuation(it, "}")
        PSubsetStruct(parent, args)
    }
    assert(typ.required == req)
    typ
  }

  def ptype_exprs(it: TokenIterator): Array[PType] =
    base_seq_parser(ptype_expr)(it)

  def type_exprs(it: TokenIterator): Array[Type] =
    base_seq_parser(type_expr)(it)

  def type_expr(it: TokenIterator): Type = {
    // skip requiredness token for back-compatibility
    it.head match {
      case x: PunctuationToken if x.value == "+" =>
        consumeToken(it)
      case _ =>
    }

    val typ = identifier(it) match {
      case "Interval" =>
        punctuation(it, "[")
        val pointType = type_expr(it)
        punctuation(it, "]")
        TInterval(pointType)
      case "Boolean" => TBoolean
      case "Int32" => TInt32
      case "Int64" => TInt64
      case "Int" => TInt32
      case "Float32" => TFloat32
      case "Float64" => TFloat64
      case "String" => TString
      case "Locus" =>
        punctuation(it, "(")
        val rg = identifier(it)
        punctuation(it, ")")
        TLocus(rg)
      case "Call" => TCall
      case "Stream" =>
        punctuation(it, "[")
        val elementType = type_expr(it)
        punctuation(it, "]")
        TStream(elementType)
      case "Array" =>
        punctuation(it, "[")
        val elementType = type_expr(it)
        punctuation(it, "]")
        TArray(elementType)
      case "NDArray" =>
        punctuation(it, "[")
        val elementType = type_expr(it)
        punctuation(it, ",")
        val nDims = int32_literal(it)
        punctuation(it, "]")
        TNDArray(elementType, Nat(nDims))
      case "Set" =>
        punctuation(it, "[")
        val elementType = type_expr(it)
        punctuation(it, "]")
        TSet(elementType)
      case "Dict" =>
        punctuation(it, "[")
        val keyType = type_expr(it)
        punctuation(it, ",")
        val valueType = type_expr(it)
        punctuation(it, "]")
        TDict(keyType, valueType)
      case "Tuple" =>
        punctuation(it, "[")
        val types = repsepUntil(it, type_expr, PunctuationToken(","), PunctuationToken("]"))
        punctuation(it, "]")
        TTuple(types: _*)
      case "TupleSubset" =>
        punctuation(it, "[")
        val fields =
          repsepUntil(it, tuple_subset_field, PunctuationToken(","), PunctuationToken("]"))
        punctuation(it, "]")
        TTuple(fields.map { case (idx, t) => TupleField(idx, t) })
      case "Struct" =>
        punctuation(it, "{")
        val args = repsepUntil(it, type_field, PunctuationToken(","), PunctuationToken("}"))
        punctuation(it, "}")
        val fields = args.zipWithIndex.map { case ((id, t), i) => Field(id, t, i) }
        TStruct(fields)
      case "Union" =>
        punctuation(it, "{")
        val args = repsepUntil(it, type_field, PunctuationToken(","), PunctuationToken("}"))
        punctuation(it, "}")
        val cases = args.zipWithIndex.map { case ((id, t), i) => Case(id, t, i) }
        TUnion(cases)
      case "Void" => TVoid
    }
    typ
  }

  def sort_fields(it: TokenIterator): Array[SortField] =
    base_seq_parser(sort_field)(it)

  def sort_field(it: TokenIterator): SortField = {
    val sortField = identifier(it)
    val field = sortField.substring(1)
    val sortOrder = SortOrder.parse(sortField.substring(0, 1))
    SortField(field, sortOrder)
  }

  def keys(it: TokenIterator): Array[String] = {
    punctuation(it, "[")
    val keys = repsepUntil(it, identifier, PunctuationToken(","), PunctuationToken("]"))
    punctuation(it, "]")
    keys
  }

  def trailing_keys(it: TokenIterator): Array[String] = {
    it.head match {
      case x: PunctuationToken if x.value == "]" =>
        Array.empty[String]
      case x: PunctuationToken if x.value == "," =>
        punctuation(it, ",")
        repsepUntil(it, identifier, PunctuationToken(","), PunctuationToken("]"))
    }
  }

  def rvd_type_expr(it: TokenIterator): RVDType = {
    identifier(it) match {
      case "RVDType" | "OrderedRVDType" =>
        punctuation(it, "{")
        identifier(it, "key")
        punctuation(it, ":")
        punctuation(it, "[")
        val partitionKey = keys(it)
        val restKey = trailing_keys(it)
        punctuation(it, "]")
        punctuation(it, ",")
        identifier(it, "row")
        punctuation(it, ":")
        val rowType = tcoerce[PStruct](ptype_expr(it))
        RVDType(rowType, partitionKey ++ restKey)
    }
  }

  def table_type_expr(it: TokenIterator): TableType = {
    identifier(it, "Table")
    punctuation(it, "{")

    identifier(it, "global")
    punctuation(it, ":")
    val globalType = tcoerce[TStruct](type_expr(it))
    punctuation(it, ",")

    identifier(it, "key")
    punctuation(it, ":")
    val key = opt(it, keys).getOrElse(Array.empty[String])
    punctuation(it, ",")

    identifier(it, "row")
    punctuation(it, ":")
    val rowType = tcoerce[TStruct](type_expr(it))
    punctuation(it, "}")
    TableType(rowType, key.toFastSeq, tcoerce[TStruct](globalType))
  }

  def matrix_type_expr(it: TokenIterator): MatrixType = {
    identifier(it, "Matrix")
    punctuation(it, "{")

    identifier(it, "global")
    punctuation(it, ":")
    val globalType = tcoerce[TStruct](type_expr(it))
    punctuation(it, ",")

    identifier(it, "col_key")
    punctuation(it, ":")
    val colKey = keys(it)
    punctuation(it, ",")

    identifier(it, "col")
    punctuation(it, ":")
    val colType = tcoerce[TStruct](type_expr(it))
    punctuation(it, ",")

    identifier(it, "row_key")
    punctuation(it, ":")
    punctuation(it, "[")
    val rowPartitionKey = keys(it)
    val rowRestKey = trailing_keys(it)
    punctuation(it, "]")
    punctuation(it, ",")

    identifier(it, "row")
    punctuation(it, ":")
    val rowType = tcoerce[TStruct](type_expr(it))
    punctuation(it, ",")

    identifier(it, "entry")
    punctuation(it, ":")
    val entryType = tcoerce[TStruct](type_expr(it))
    punctuation(it, "}")

    MatrixType(
      tcoerce[TStruct](globalType),
      colKey,
      colType,
      rowPartitionKey ++ rowRestKey,
      rowType,
      entryType,
    )
  }

  def agg_op(it: TokenIterator): AggOp =
    AggOp.fromString(identifier(it))

  def agg_state_signature(env: IRParserEnvironment)(it: TokenIterator): AggStateSig = {
    punctuation(it, "(")
    val sig = identifier(it) match {
      case "TypedStateSig" =>
        val pt = vtwr_expr(it)
        TypedStateSig(pt)
      case "DownsampleStateSig" =>
        val labelType = vtwr_expr(it)
        DownsampleStateSig(labelType)
      case "TakeStateSig" =>
        val pt = vtwr_expr(it)
        TakeStateSig(pt)
      case "ReservoirSampleStateSig" =>
        val pt = vtwr_expr(it)
        ReservoirSampleStateSig(pt)
      case "DensifyStateSig" =>
        val pt = vtwr_expr(it)
        DensifyStateSig(pt)
      case "TakeByStateSig" =>
        val vt = vtwr_expr(it)
        val kt = vtwr_expr(it)
        TakeByStateSig(vt, kt, Ascending)
      case "CollectStateSig" =>
        val pt = vtwr_expr(it)
        CollectStateSig(pt)
      case "CollectAsSetStateSig" =>
        val pt = vtwr_expr(it)
        CollectAsSetStateSig(pt)
      case "CallStatsStateSig" => CallStatsStateSig()
      case "ArrayAggStateSig" =>
        val nested = agg_state_signatures(env)(it)
        ArrayAggStateSig(nested)
      case "GroupedStateSig" =>
        val kt = vtwr_expr(it)
        val nested = agg_state_signatures(env)(it)
        GroupedStateSig(kt, nested)
      case "ApproxCDFStateSig" => ApproxCDFStateSig()
      case "FoldStateSig" =>
        val vtwr = vtwr_expr(it)
        val accumName = name(it)
        val otherAccumName = name(it)
        val combIR = ir_value_expr(env)(it).run()
        FoldStateSig(vtwr.canonicalEmitType, accumName, otherAccumName, combIR)
    }
    punctuation(it, ")")
    sig
  }

  def agg_state_signatures(env: IRParserEnvironment)(it: TokenIterator): Array[AggStateSig] =
    base_seq_parser(agg_state_signature(env))(it)

  def p_agg_sigs(env: IRParserEnvironment)(it: TokenIterator): Array[PhysicalAggSig] =
    base_seq_parser(p_agg_sig(env))(it)

  def p_agg_sig(env: IRParserEnvironment)(it: TokenIterator): PhysicalAggSig = {
    punctuation(it, "(")
    val sig = identifier(it) match {
      case "Grouped" =>
        val pt = vtwr_expr(it)
        val nested = p_agg_sigs(env)(it)
        GroupedAggSig(pt, nested)
      case "ArrayLen" =>
        val knownLength = boolean_literal(it)
        val nested = p_agg_sigs(env)(it)
        ArrayLenAggSig(knownLength, nested)
      case "AggElements" =>
        val nested = p_agg_sigs(env)(it)
        AggElementsAggSig(nested)
      case op =>
        val state = agg_state_signature(env)(it)
        PhysicalAggSig(AggOp.fromString(op), state)
    }
    punctuation(it, ")")
    sig
  }

  def agg_signature(it: TokenIterator): AggSignature = {
    punctuation(it, "(")
    val op = agg_op(it)
    val initArgs = type_exprs(it)
    val seqOpArgs = type_exprs(it)
    punctuation(it, ")")
    AggSignature(op, initArgs, seqOpArgs)
  }

  def agg_signatures(it: TokenIterator): Array[AggSignature] =
    base_seq_parser(agg_signature)(it)

  def ir_value(it: TokenIterator): (Type, Any) = {
    val typ = type_expr(it)
    val s = string_literal(it)
    val vJSON = JsonMethods.parse(s)
    val v = JSONAnnotationImpex.importAnnotation(vJSON, typ)
    (typ, v)
  }

  def named_value_irs(env: IRParserEnvironment)(it: TokenIterator)
    : StackFrame[Array[(String, IR)]] =
    repUntil(it, named_value_ir(env), PunctuationToken(")"))

  def named_value_ir(env: IRParserEnvironment)(it: TokenIterator): StackFrame[(String, IR)] = {
    punctuation(it, "(")
    val name = identifier(it)
    ir_value_expr(env)(it).map { value =>
      punctuation(it, ")")
      (name, value)
    }
  }

  def ir_value_exprs(env: IRParserEnvironment)(it: TokenIterator): StackFrame[Array[IR]] = {
    punctuation(it, "(")
    for {
      irs <- ir_value_children(env)(it)
      _ = punctuation(it, ")")
    } yield irs
  }

  def ir_value_children(env: IRParserEnvironment)(it: TokenIterator): StackFrame[Array[IR]] =
    repUntil(it, ir_value_expr(env), PunctuationToken(")"))

  def ir_value_expr(env: IRParserEnvironment)(it: TokenIterator): StackFrame[IR] = {
    punctuation(it, "(")
    for {
      ir <- call(ir_value_expr_1(env)(it))
      _ = punctuation(it, ")")
    } yield ir
  }

  def apply_like(
    env: IRParserEnvironment,
    cons: (String, Seq[Type], Seq[IR], Type, Int) => IR,
  )(
    it: TokenIterator
  ): StackFrame[IR] = {
    val errorID = int32_literal(it)
    val function = identifier(it)
    val typeArgs = type_exprs(it)
    val rt = type_expr(it)
    ir_value_children(env)(it).map(args => cons(function, typeArgs, args, rt, errorID))
  }

  def ir_value_expr_1(env: IRParserEnvironment)(it: TokenIterator): StackFrame[IR] = {
    identifier(it) match {
      case "I32" => done(I32(int32_literal(it)))
      case "I64" => done(I64(int64_literal(it)))
      case "F32" => done(F32(float32_literal(it)))
      case "F64" => done(F64(float64_literal(it)))
      case "Str" => done(Str(string_literal(it)))
      case "UUID4" => done(UUID4(identifier(it)))
      case "True" => done(True())
      case "False" => done(False())
      case "Literal" =>
        val (t, v) = ir_value(it)
        done(Literal.coerce(t, v))
      case "EncodedLiteral" =>
        val typ = type_expr(it)
        val encodedValue = Base64.getDecoder.decode(string_literal(it))
        val codec = TypedCodecSpec(
          EType.fromPythonTypeEncoding(typ),
          typ,
          BufferSpec.unblockedUncompressed,
        )
        done(EncodedLiteral(codec, Array(encodedValue)))
      case "Void" => done(Void())
      case "Cast" =>
        val typ = type_expr(it)
        ir_value_expr(env)(it).map(Cast(_, typ))
      case "CastRename" =>
        val typ = type_expr(it)
        ir_value_expr(env)(it).map(CastRename(_, typ))
      case "NA" => done(NA(type_expr(it)))
      case "IsNA" => ir_value_expr(env)(it).map(IsNA)
      case "Coalesce" =>
        for {
          children <- ir_value_children(env)(it)
          _ = require(children.nonEmpty)
        } yield Coalesce(children)
      case "If" =>
        for {
          cond <- ir_value_expr(env)(it)
          consq <- ir_value_expr(env)(it)
          altr <- ir_value_expr(env)(it)
        } yield If(cond, consq, altr)
      case "Switch" =>
        for {
          x <- ir_value_expr(env)(it)
          default <- ir_value_expr(env)(it)
          cases <- ir_value_children(env)(it)
        } yield Switch(x, default, cases)
      case "Let" | "Block" =>
        val names =
          repUntilNonStackSafe(it, it => (identifier(it), name(it)), PunctuationToken("("))
        val values = new Array[IR](names.length)
        for {
          _ <- names.indices.foldLeft(done(())) { case (update, i) =>
            for {
              _ <- update
              value <- ir_value_expr(env)(it)
            } yield values.update(i, value)
          }
          body <- ir_value_expr(env)(it)
        } yield {
          val bindings = (names, values).zipped.map { case ((bindType, name), value) =>
            val scope = bindType match {
              case "eval" => Scope.EVAL
              case "agg" => Scope.AGG
              case "scan" => Scope.SCAN
            }
            Binding(name, value, scope)
          }
          Block(bindings, body)
        }
      case "AggLet" =>
        val n = name(it)
        val isScan = boolean_literal(it)
        for {
          value <- ir_value_expr(env)(it)
          body <- ir_value_expr(env)(it)
        } yield AggLet(n, value, body, isScan)
      case "TailLoop" =>
        val n = name(it)
        val paramNames = names(it)
        val resultType = type_expr(it)
        for {
          paramIRs <- fillArray(paramNames.length)(ir_value_expr(env)(it))
          params = paramNames.zip(paramIRs)
          body <- ir_value_expr(env)(it)
        } yield TailLoop(n, params, resultType, body)
      case "Recur" =>
        val n = name(it)
        ir_value_children(env)(it).map(args => Recur(n, args, null))
      case "Ref" =>
        val id = name(it)
        done(Ref(id, null))
      case "RelationalRef" =>
        val id = name(it)
        val t = type_expr(it)
        done(RelationalRef(id, t))
      case "RelationalLet" =>
        val n = name(it)
        for {
          value <- ir_value_expr(env)(it)
          body <- ir_value_expr(env)(it)
        } yield RelationalLet(n, value, body)
      case "ApplyBinaryPrimOp" =>
        val op = BinaryOp.fromString(identifier(it))
        for {
          l <- ir_value_expr(env)(it)
          r <- ir_value_expr(env)(it)
        } yield ApplyBinaryPrimOp(op, l, r)
      case "ApplyUnaryPrimOp" =>
        val op = UnaryOp.fromString(identifier(it))
        ir_value_expr(env)(it).map(ApplyUnaryPrimOp(op, _))
      case "ApplyComparisonOp" =>
        val opName = identifier(it)
        for {
          l <- ir_value_expr(env)(it)
          r <- ir_value_expr(env)(it)
        } yield ApplyComparisonOp(ComparisonOp.fromString(opName), l, r)
      case "MakeArray" =>
        val typ = opt(it, type_expr).map(_.asInstanceOf[TArray]).orNull
        ir_value_children(env)(it).map(args => MakeArray(args, typ))
      case "MakeStream" =>
        val typ = opt(it, type_expr).map(_.asInstanceOf[TStream]).orNull
        val requiresMemoryManagementPerElement = boolean_literal(it)
        ir_value_children(env)(it).map { args =>
          MakeStream(args, typ, requiresMemoryManagementPerElement)
        }
      case "ArrayRef" =>
        val errorID = int32_literal(it)
        for {
          a <- ir_value_expr(env)(it)
          i <- ir_value_expr(env)(it)
        } yield ArrayRef(a, i, errorID)
      case "ArraySlice" =>
        val errorID = int32_literal(it)
        ir_value_children(env)(it).map {
          case Array(a, start, step) => ArraySlice(a, start, None, step, errorID)
          case Array(a, start, stop, step) => ArraySlice(a, start, Some(stop), step, errorID)
        }
      case "RNGStateLiteral" =>
        done(RNGStateLiteral())
      case "RNGSplit" =>
        for {
          state <- ir_value_expr(env)(it)
          dynBitstring <- ir_value_expr(env)(it)
        } yield RNGSplit(state, dynBitstring)
      case "ArrayLen" => ir_value_expr(env)(it).map(ArrayLen)
      case "StreamLen" => ir_value_expr(env)(it).map(StreamLen)
      case "StreamIota" =>
        val requiresMemoryManagementPerElement = boolean_literal(it)
        for {
          start <- ir_value_expr(env)(it)
          step <- ir_value_expr(env)(it)
        } yield StreamIota(start, step, requiresMemoryManagementPerElement)
      case "StreamRange" =>
        val errorID = int32_literal(it)
        val requiresMemoryManagementPerElement = boolean_literal(it)
        for {
          start <- ir_value_expr(env)(it)
          stop <- ir_value_expr(env)(it)
          step <- ir_value_expr(env)(it)
        } yield StreamRange(start, stop, step, requiresMemoryManagementPerElement, errorID)
      case "StreamGrouped" =>
        for {
          s <- ir_value_expr(env)(it)
          groupSize <- ir_value_expr(env)(it)
        } yield StreamGrouped(s, groupSize)
      case "ArrayZeros" => ir_value_expr(env)(it).map(ArrayZeros)
      case "ArraySort" =>
        val l = name(it)
        val r = name(it)
        for {
          a <- ir_value_expr(env)(it)
          lessThan <- ir_value_expr(env)(it)
        } yield ArraySort(a, l, r, lessThan)
      case "ArrayMaximalIndependentSet" =>
        val hasTieBreaker = boolean_literal(it)
        val bindings = if (hasTieBreaker) Some(name(it) -> name(it)) else None
        for {
          edges <- ir_value_expr(env)(it)
          tieBreaker <- if (hasTieBreaker) {
            val Some((left, right)) = bindings
            ir_value_expr(env)(it).map(tbf => Some((left, right, tbf)))
          } else {
            done(None)
          }
        } yield ArrayMaximalIndependentSet(edges, tieBreaker)
      case "MakeNDArray" =>
        val errorID = int32_literal(it)
        for {
          data <- ir_value_expr(env)(it)
          shape <- ir_value_expr(env)(it)
          rowMajor <- ir_value_expr(env)(it)
        } yield MakeNDArray(data, shape, rowMajor, errorID)
      case "NDArrayShape" => ir_value_expr(env)(it).map(NDArrayShape)
      case "NDArrayReshape" =>
        val errorID = int32_literal(it)
        for {
          nd <- ir_value_expr(env)(it)
          shape <- ir_value_expr(env)(it)
        } yield NDArrayReshape(nd, shape, errorID)
      case "NDArrayConcat" =>
        val axis = int32_literal(it)
        ir_value_expr(env)(it).map(nds => NDArrayConcat(nds, axis))
      case "NDArrayMap" =>
        val n = name(it)
        for {
          nd <- ir_value_expr(env)(it)
          body <- ir_value_expr(env)(it)
        } yield NDArrayMap(nd, n, body)
      case "NDArrayMap2" =>
        val errorID = int32_literal(it)
        val lName = name(it)
        val rName = name(it)
        for {
          l <- ir_value_expr(env)(it)
          r <- ir_value_expr(env)(it)
          body <- ir_value_expr(env)(it)
        } yield NDArrayMap2(l, r, lName, rName, body, errorID)
      case "NDArrayReindex" =>
        val indexExpr = int32_literals(it)
        ir_value_expr(env)(it).map(nd => NDArrayReindex(nd, indexExpr))
      case "NDArrayAgg" =>
        val axes = int32_literals(it)
        ir_value_expr(env)(it).map(nd => NDArrayAgg(nd, axes))
      case "NDArrayRef" =>
        val errorID = int32_literal(it)
        for {
          nd <- ir_value_expr(env)(it)
          idxs <- ir_value_children(env)(it)
        } yield NDArrayRef(nd, idxs, errorID)
      case "NDArraySlice" =>
        for {
          nd <- ir_value_expr(env)(it)
          slices <- ir_value_expr(env)(it)
        } yield NDArraySlice(nd, slices)
      case "NDArrayFilter" =>
        for {
          nd <- ir_value_expr(env)(it)
          filters <- repUntil(it, ir_value_expr(env), PunctuationToken(")"))
        } yield NDArrayFilter(nd, filters.toFastSeq)
      case "NDArrayMatMul" =>
        val errorID = int32_literal(it)
        for {
          l <- ir_value_expr(env)(it)
          r <- ir_value_expr(env)(it)
        } yield NDArrayMatMul(l, r, errorID)
      case "NDArrayWrite" =>
        for {
          nd <- ir_value_expr(env)(it)
          path <- ir_value_expr(env)(it)
        } yield NDArrayWrite(nd, path)
      case "NDArrayQR" =>
        val errorID = int32_literal(it)
        val mode = string_literal(it)
        ir_value_expr(env)(it).map(nd => NDArrayQR(nd, mode, errorID))
      case "NDArraySVD" =>
        val errorID = int32_literal(it)
        val fullMatrices = boolean_literal(it)
        val computeUV = boolean_literal(it)
        ir_value_expr(env)(it).map(nd => NDArraySVD(nd, fullMatrices, computeUV, errorID))
      case "NDArrayEigh" =>
        val errorID = int32_literal(it)
        val eigvalsOnly = boolean_literal(it)
        ir_value_expr(env)(it).map(nd => NDArrayEigh(nd, eigvalsOnly, errorID))
      case "NDArrayInv" =>
        val errorID = int32_literal(it)
        ir_value_expr(env)(it).map(nd => NDArrayInv(nd, errorID))
      case "ToSet" => ir_value_expr(env)(it).map(ToSet)
      case "ToDict" => ir_value_expr(env)(it).map(ToDict)
      case "ToArray" => ir_value_expr(env)(it).map(ToArray)
      case "CastToArray" => ir_value_expr(env)(it).map(CastToArray)
      case "ToStream" =>
        val requiresMemoryManagementPerElement = boolean_literal(it)
        ir_value_expr(env)(it).map(a => ToStream(a, requiresMemoryManagementPerElement))
      case "LowerBoundOnOrderedCollection" =>
        val onKey = boolean_literal(it)
        for {
          col <- ir_value_expr(env)(it)
          elem <- ir_value_expr(env)(it)
        } yield LowerBoundOnOrderedCollection(col, elem, onKey)
      case "GroupByKey" => ir_value_expr(env)(it).map(GroupByKey)
      case "StreamMap" =>
        val n = name(it)
        for {
          a <- ir_value_expr(env)(it)
          body <- ir_value_expr(env)(it)
        } yield StreamMap(a, n, body)
      case "StreamTake" =>
        for {
          a <- ir_value_expr(env)(it)
          num <- ir_value_expr(env)(it)
        } yield StreamTake(a, num)
      case "StreamDrop" =>
        for {
          a <- ir_value_expr(env)(it)
          num <- ir_value_expr(env)(it)
        } yield StreamDrop(a, num)
      case "StreamZip" =>
        val errorID = int32_literal(it)
        val behavior = identifier(it) match {
          case "AssertSameLength" => ArrayZipBehavior.AssertSameLength
          case "TakeMinLength" => ArrayZipBehavior.TakeMinLength
          case "ExtendNA" => ArrayZipBehavior.ExtendNA
          case "AssumeSameLength" => ArrayZipBehavior.AssumeSameLength
        }
        val ns = names(it)
        for {
          as <- ns.mapRecur(_ => ir_value_expr(env)(it))
          body <- ir_value_expr(env)(it)
        } yield StreamZip(as, ns, body, behavior, errorID)
      case "StreamZipJoinProducers" =>
        val key = identifiers(it)
        val ctxName = name(it)
        val curKey = name(it)
        val curVals = name(it)
        for {
          ctxs <- ir_value_expr(env)(it)
          makeProducer <- ir_value_expr(env)(it)
          body <-
            ir_value_expr(env)(it)
        } yield StreamZipJoinProducers(ctxs, ctxName, makeProducer, key, curKey, curVals, body)
      case "StreamZipJoin" =>
        val nStreams = int32_literal(it)
        val key = identifiers(it)
        val curKey = name(it)
        val curVals = name(it)
        for {
          streams <- (0 until nStreams).mapRecur(_ => ir_value_expr(env)(it))
          body <-
            ir_value_expr(env)(it)
        } yield StreamZipJoin(streams, key, curKey, curVals, body)
      case "StreamMultiMerge" =>
        val key = identifiers(it)
        for {
          streams <- ir_value_exprs(env)(it)
        } yield StreamMultiMerge(streams, key)
      case "StreamFilter" =>
        val n = name(it)
        for {
          a <- ir_value_expr(env)(it)
          body <- ir_value_expr(env)(it)
        } yield StreamFilter(a, n, body)
      case "StreamTakeWhile" =>
        val n = name(it)
        for {
          a <- ir_value_expr(env)(it)
          body <- ir_value_expr(env)(it)
        } yield StreamTakeWhile(a, n, body)
      case "StreamDropWhile" =>
        val n = name(it)
        for {
          a <- ir_value_expr(env)(it)
          body <- ir_value_expr(env)(it)
        } yield StreamDropWhile(a, n, body)
      case "StreamFlatMap" =>
        val n = name(it)
        for {
          a <- ir_value_expr(env)(it)
          body <- ir_value_expr(env)(it)
        } yield StreamFlatMap(a, n, body)
      case "StreamFold" =>
        val accumName = name(it)
        val valueName = name(it)
        for {
          a <- ir_value_expr(env)(it)
          zero <- ir_value_expr(env)(it)
          body <- ir_value_expr(env)(it)
        } yield StreamFold(a, zero, accumName, valueName, body)
      case "StreamFold2" =>
        val accumNames = names(it)
        val valueName = name(it)
        for {
          a <- ir_value_expr(env)(it)
          accIRs <- fillArray(accumNames.length)(ir_value_expr(env)(it))
          accs = accumNames.zip(accIRs)
          seqs <- fillArray(accs.length)(ir_value_expr(env)(it))
          res <- ir_value_expr(env)(it)
        } yield StreamFold2(a, accs, valueName, seqs, res)
      case "StreamScan" =>
        val accumName = name(it)
        val valueName = name(it)
        for {
          a <- ir_value_expr(env)(it)
          zero <- ir_value_expr(env)(it)
          body <- ir_value_expr(env)(it)
        } yield StreamScan(a, zero, accumName, valueName, body)
      case "StreamWhiten" =>
        val newChunk = identifier(it)
        val prevWindow = identifier(it)
        val vecSize = int32_literal(it)
        val windowSize = int32_literal(it)
        val chunkSize = int32_literal(it)
        val blockSize = int32_literal(it)
        val normalizeAfterWhitening = boolean_literal(it)
        for {
          stream <- ir_value_expr(env)(it)
        } yield StreamWhiten(stream, newChunk, prevWindow, vecSize, windowSize, chunkSize,
          blockSize, normalizeAfterWhitening)
      case "StreamJoinRightDistinct" =>
        val lKey = identifiers(it)
        val rKey = identifiers(it)
        val l = name(it)
        val r = name(it)
        val joinType = identifier(it)
        for {
          left <- ir_value_expr(env)(it)
          right <- ir_value_expr(env)(it)
          join <- ir_value_expr(env)(it)
        } yield StreamJoinRightDistinct(left, right, lKey, rKey, l, r, join, joinType)
      case "StreamLeftIntervalJoin" =>
        val lKeyFieldName = identifier(it)
        val rIntervalName = identifier(it)
        val lname = name(it)
        val rname = name(it)
        for {
          left <- ir_value_expr(env)(it)
          right <- ir_value_expr(env)(it)
          body <- ir_value_expr(env)(it)
        } yield StreamLeftIntervalJoin(left, right, lKeyFieldName, rIntervalName, lname, rname,
          body)

      case "StreamFor" =>
        val n = name(it)
        for {
          a <- ir_value_expr(env)(it)
          body <- ir_value_expr(env)(it)
        } yield StreamFor(a, n, body)
      case "StreamAgg" =>
        val n = name(it)
        for {
          a <- ir_value_expr(env)(it)
          query <- ir_value_expr(env)(it)
        } yield StreamAgg(a, n, query)
      case "StreamAggScan" =>
        val n = name(it)
        for {
          a <- ir_value_expr(env)(it)
          query <- ir_value_expr(env)(it)
        } yield StreamAggScan(a, n, query)
      case "RunAgg" =>
        val signatures = agg_state_signatures(env)(it)
        for {
          body <- ir_value_expr(env)(it)
          result <- ir_value_expr(env)(it)
        } yield RunAgg(body, result, signatures)
      case "RunAggScan" =>
        val n = name(it)
        val signatures = agg_state_signatures(env)(it)
        for {
          array <- ir_value_expr(env)(it)
          init <- ir_value_expr(env)(it)
          seq <- ir_value_expr(env)(it)
          result <- ir_value_expr(env)(it)
        } yield RunAggScan(array, n, init, seq, result, signatures)
      case "AggFilter" =>
        val isScan = boolean_literal(it)
        for {
          cond <- ir_value_expr(env)(it)
          aggIR <- ir_value_expr(env)(it)
        } yield AggFilter(cond, aggIR, isScan)
      case "AggExplode" =>
        val n = name(it)
        val isScan = boolean_literal(it)
        for {
          a <- ir_value_expr(env)(it)
          aggBody <- ir_value_expr(env)(it)
        } yield AggExplode(a, n, aggBody, isScan)
      case "AggGroupBy" =>
        val isScan = boolean_literal(it)
        for {
          key <- ir_value_expr(env)(it)
          aggIR <- ir_value_expr(env)(it)
        } yield AggGroupBy(key, aggIR, isScan)
      case "AggArrayPerElement" =>
        val elementName = name(it)
        val indexName = name(it)
        val isScan = boolean_literal(it)
        val hasKnownLength = boolean_literal(it)
        for {
          a <- ir_value_expr(env)(it)
          aggBody <- ir_value_expr(env)(it)
          knownLength <- if (hasKnownLength) ir_value_expr(env)(it).map(Some(_)) else done(None)
        } yield AggArrayPerElement(a, elementName, indexName, aggBody, knownLength, isScan)
      case "ApplyAggOp" =>
        val aggOp = agg_op(it)
        for {
          initOpArgs <- ir_value_exprs(env)(it)
          seqOpArgs <- ir_value_exprs(env)(it)
          aggSig = AggSignature(aggOp, null, null)
        } yield ApplyAggOp(initOpArgs, seqOpArgs, aggSig)
      case "ApplyScanOp" =>
        val aggOp = agg_op(it)
        for {
          initOpArgs <- ir_value_exprs(env)(it)
          seqOpArgs <- ir_value_exprs(env)(it)
          aggSig = AggSignature(aggOp, null, null)
        } yield ApplyScanOp(initOpArgs, seqOpArgs, aggSig)
      case "AggFold" =>
        val accumName = name(it)
        val otherAccumName = name(it)
        val isScan = boolean_literal(it)
        for {
          zero <- ir_value_expr(env)(it)
          seqOp <- ir_value_expr(env)(it)
          combOp <- ir_value_expr(env)(it)
        } yield AggFold(zero, seqOp, combOp, accumName, otherAccumName, isScan)
      case "InitOp" =>
        val i = int32_literal(it)
        val aggSig = p_agg_sig(env)(it)
        ir_value_exprs(env)(it).map(args => InitOp(i, args, aggSig))
      case "SeqOp" =>
        val i = int32_literal(it)
        val aggSig = p_agg_sig(env)(it)
        ir_value_exprs(env)(it).map(args => SeqOp(i, args, aggSig))
      case "CombOp" =>
        val i1 = int32_literal(it)
        val i2 = int32_literal(it)
        val aggSig = p_agg_sig(env)(it)
        done(CombOp(i1, i2, aggSig))
      case "ResultOp" =>
        val i = int32_literal(it)
        val aggSig = p_agg_sig(env)(it)
        done(ResultOp(i, aggSig))
      case "AggStateValue" =>
        val i = int32_literal(it)
        val sig = agg_state_signature(env)(it)
        done(AggStateValue(i, sig))
      case "InitFromSerializedValue" =>
        val i = int32_literal(it)
        val sig = agg_state_signature(env)(it)
        ir_value_expr(env)(it).map(value => InitFromSerializedValue(i, value, sig))
      case "CombOpValue" =>
        val i = int32_literal(it)
        val sig = p_agg_sig(env)(it)
        ir_value_expr(env)(it).map(value => CombOpValue(i, value, sig))
      case "SerializeAggs" =>
        val i = int32_literal(it)
        val i2 = int32_literal(it)
        val spec = BufferSpec.parse(string_literal(it))
        val aggSigs = agg_state_signatures(env)(it)
        done(SerializeAggs(i, i2, spec, aggSigs))
      case "DeserializeAggs" =>
        val i = int32_literal(it)
        val i2 = int32_literal(it)
        val spec = BufferSpec.parse(string_literal(it))
        val aggSigs = agg_state_signatures(env)(it)
        done(DeserializeAggs(i, i2, spec, aggSigs))
      case "Begin" => ir_value_children(env)(it).map(Begin(_))
      case "MakeStruct" => named_value_irs(env)(it).map(MakeStruct(_))
      case "SelectFields" =>
        val fields = identifiers(it)
        ir_value_expr(env)(it).map(old => SelectFields(old, fields))
      case "InsertFields" =>
        for {
          old <- ir_value_expr(env)(it)
          fieldOrder = opt(it, string_literals)
          fields <- named_value_irs(env)(it)
        } yield InsertFields(old, fields, fieldOrder.map(_.toFastSeq))
      case "GetField" =>
        val name = identifier(it)
        ir_value_expr(env)(it).map(s => GetField(s, name))
      case "MakeTuple" =>
        val indices = int32_literals(it)
        ir_value_children(env)(it).map(args => MakeTuple(indices.zip(args)))
      case "GetTupleElement" =>
        val idx = int32_literal(it)
        ir_value_expr(env)(it).map(tuple => GetTupleElement(tuple, idx))
      case "Die" =>
        val typ = type_expr(it)
        val errorID = int32_literal(it)
        ir_value_expr(env)(it).map(msg => Die(msg, typ, errorID))
      case "Trap" =>
        ir_value_expr(env)(it).map(child => Trap(child))
      case "ConsoleLog" =>
        for {
          msg <- ir_value_expr(env)(it)
          result <- ir_value_expr(env)(it)
        } yield ConsoleLog(msg, result)
      case "ApplySeeded" =>
        val function = identifier(it)
        val staticUID = int64_literal(it)
        val rt = type_expr(it)
        for {
          rngState <- ir_value_expr(env)(it)
          args <- ir_value_children(env)(it)
        } yield ApplySeeded(function, args, rngState, staticUID, rt)
      case "ApplyIR" =>
        apply_like(env, ApplyIR)(it)
      case "ApplySpecial" =>
        apply_like(env, ApplySpecial)(it)
      case "Apply" =>
        apply_like(env, Apply)(it)
      case "MatrixCount" =>
        matrix_ir(env)(it).map(MatrixCount)
      case "TableCount" =>
        table_ir(env)(it).map(TableCount)
      case "TableGetGlobals" =>
        table_ir(env)(it).map(TableGetGlobals)
      case "TableCollect" =>
        table_ir(env)(it).map(TableCollect)
      case "TableAggregate" =>
        for {
          child <- table_ir(env)(it)
          query <- ir_value_expr(env)(it)
        } yield TableAggregate(child, query)
      case "TableToValueApply" =>
        val config = string_literal(it)
        table_ir(env)(it).map { child =>
          TableToValueApply(child, RelationalFunctions.lookupTableToValue(env.ctx, config))
        }
      case "MatrixToValueApply" =>
        val config = string_literal(it)
        matrix_ir(env)(it).map { child =>
          MatrixToValueApply(child, RelationalFunctions.lookupMatrixToValue(env.ctx, config))
        }
      case "BlockMatrixToValueApply" =>
        val config = string_literal(it)
        blockmatrix_ir(env)(it).map { child =>
          BlockMatrixToValueApply(
            child,
            RelationalFunctions.lookupBlockMatrixToValue(env.ctx, config),
          )
        }
      case "BlockMatrixCollect" =>
        blockmatrix_ir(env)(it).map(BlockMatrixCollect)
      case "TableWrite" =>
        implicit val formats = TableWriter.formats
        val writerStr = string_literal(it)
        table_ir(env)(it).map(child => TableWrite(child, deserialize[TableWriter](writerStr)))
      case "TableMultiWrite" =>
        implicit val formats = WrappedMatrixNativeMultiWriter.formats
        val writerStr = string_literal(it)
        table_ir_children(env)(it).map { children =>
          TableMultiWrite(children, deserialize[WrappedMatrixNativeMultiWriter](writerStr))
        }
      case "MatrixAggregate" =>
        for {
          child <- matrix_ir(env)(it)
          query <- ir_value_expr(env)(it)
        } yield MatrixAggregate(child, query)
      case "MatrixWrite" =>
        val writerStr = string_literal(it)
        implicit val formats: Formats = MatrixWriter.formats
        val writer = deserialize[MatrixWriter](writerStr)
        matrix_ir(env)(it).map(child => MatrixWrite(child, writer))
      case "MatrixMultiWrite" =>
        val writerStr = string_literal(it)
        implicit val formats = MatrixNativeMultiWriter.formats
        val writer = deserialize[MatrixNativeMultiWriter](writerStr)
        matrix_ir_children(env)(it).map(children => MatrixMultiWrite(children, writer))
      case "BlockMatrixWrite" =>
        val writerStr = string_literal(it)
        implicit val formats: Formats = BlockMatrixWriter.formats
        val writer = deserialize[BlockMatrixWriter](writerStr)
        blockmatrix_ir(env)(it).map(child => BlockMatrixWrite(child, writer))
      case "BlockMatrixMultiWrite" =>
        val writerStr = string_literal(it)
        implicit val formats: Formats = BlockMatrixWriter.formats
        val writer = deserialize[BlockMatrixMultiWriter](writerStr)
        repUntil(it, blockmatrix_ir(env), PunctuationToken(")")).map { blockMatrices =>
          BlockMatrixMultiWrite(blockMatrices.toFastSeq, writer)
        }
      case "CollectDistributedArray" =>
        val staticID = identifier(it)
        val cname = name(it)
        val gname = name(it)
        for {
          ctxs <- ir_value_expr(env)(it)
          globals <- ir_value_expr(env)(it)
          body <- ir_value_expr(env)(it)
          dynamicID <- ir_value_expr(env)(it)
        } yield CollectDistributedArray(ctxs, globals, cname, gname, body, dynamicID, staticID)
      case "JavaIR" =>
        val id = int32_literal(it)
        done(env.irMap(id).asInstanceOf[IR])
      case "ReadPartition" =>
        val requestedTypeRaw = it.head match {
          case x: IdentifierToken if x.value == "None" || x.value == "DropRowUIDs" =>
            consumeToken(it)
            Left(x.value)
          case _ =>
            Right(type_expr(it))
        }
        val reader = PartitionReader.extract(env.ctx, JsonMethods.parse(string_literal(it)))
        ir_value_expr(env)(it).map { context =>
          ReadPartition(
            context,
            requestedTypeRaw match {
              case Left("None") => reader.fullRowType
              case Left("DropRowUIDs") => reader.fullRowType.deleteKey(reader.uidFieldName)
              case Right(t) => t.asInstanceOf[TStruct]
            },
            reader,
          )
        }
      case "WritePartition" =>
        import PartitionWriter.formats
        val writer = JsonMethods.parse(string_literal(it)).extract[PartitionWriter]
        for {
          stream <- ir_value_expr(env)(it)
          ctx <- ir_value_expr(env)(it)
        } yield WritePartition(stream, ctx, writer)
      case "WriteMetadata" =>
        import MetadataWriter.formats
        val writer = JsonMethods.parse(string_literal(it)).extract[MetadataWriter]
        ir_value_expr(env)(it).map(ctx => WriteMetadata(ctx, writer))
      case "ReadValue" =>
        import ValueReader.formats
        val reader = JsonMethods.parse(string_literal(it)).extract[ValueReader]
        val typ = type_expr(it)
        ir_value_expr(env)(it).map(path => ReadValue(path, reader, typ))
      case "WriteValue" =>
        import ValueWriter.formats
        val writer = JsonMethods.parse(string_literal(it)).extract[ValueWriter]
        ir_value_children(env)(it).map {
          case Array(value, path) => WriteValue(value, path, writer)
          case Array(value, path, stagingFile) => WriteValue(value, path, writer, Some(stagingFile))
        }
      case "LiftMeOut" => ir_value_expr(env)(it).map(LiftMeOut)
      case "ReadPartition" =>
        val rowType = tcoerce[TStruct](type_expr(it))
        import PartitionReader.formats
        val reader = JsonMethods.parse(string_literal(it)).extract[PartitionReader]
        ir_value_expr(env)(it).map(context => ReadPartition(context, rowType, reader))
    }
  }

  def table_irs(env: IRParserEnvironment)(it: TokenIterator): StackFrame[Array[TableIR]] = {
    punctuation(it, "(")
    for {
      tirs <- table_ir_children(env)(it)
      _ = punctuation(it, ")")
    } yield tirs
  }

  def table_ir_children(env: IRParserEnvironment)(it: TokenIterator): StackFrame[Array[TableIR]] =
    repUntil(it, table_ir(env), PunctuationToken(")"))

  def table_ir(env: IRParserEnvironment)(it: TokenIterator): StackFrame[TableIR] = {
    punctuation(it, "(")
    for {
      ir <- call(table_ir_1(env)(it))
      _ = punctuation(it, ")")
    } yield ir
  }

  def table_ir_1(env: IRParserEnvironment)(it: TokenIterator): StackFrame[TableIR] = {
    identifier(it) match {
      case "TableKeyBy" =>
        val keys = identifiers(it)
        val isSorted = boolean_literal(it)
        table_ir(env)(it).map(child => TableKeyBy(child, keys, isSorted))
      case "TableDistinct" => table_ir(env)(it).map(TableDistinct)
      case "TableFilter" =>
        for {
          child <- table_ir(env)(it)
          pred <- ir_value_expr(env)(it)
        } yield TableFilter(child, pred)
      case "TableRead" =>
        val requestedTypeRaw = it.head match {
          case x: IdentifierToken if x.value == "None" || x.value == "DropRowUIDs" =>
            consumeToken(it)
            Left(x.value)
          case _ =>
            Right(table_type_expr(it))
        }
        val dropRows = boolean_literal(it)
        val readerStr = string_literal(it)
        val reader =
          TableReader.fromJValue(env.ctx.fs, JsonMethods.parse(readerStr).asInstanceOf[JObject])
        val requestedType = requestedTypeRaw match {
          case Left("None") => reader.fullType
          case Left("DropRowUIDs") =>
            reader.asInstanceOf[TableReaderWithExtraUID].fullTypeWithoutUIDs
          case Right(t) => t
        }
        done(TableRead(requestedType, dropRows, reader))
      case "MatrixColsTable" => matrix_ir(env)(it).map(MatrixColsTable)
      case "MatrixRowsTable" => matrix_ir(env)(it).map(MatrixRowsTable)
      case "MatrixEntriesTable" => matrix_ir(env)(it).map(MatrixEntriesTable)
      case "TableAggregateByKey" =>
        for {
          child <- table_ir(env)(it)
          expr <- ir_value_expr(env)(it)
        } yield TableAggregateByKey(child, expr)
      case "TableKeyByAndAggregate" =>
        val nPartitions = opt(it, int32_literal)
        val bufferSize = int32_literal(it)
        for {
          child <- table_ir(env)(it)
          expr <- ir_value_expr(env)(it)
          newKey <- ir_value_expr(env)(it)
        } yield TableKeyByAndAggregate(child, expr, newKey, nPartitions, bufferSize)
      case "TableRepartition" =>
        val n = int32_literal(it)
        val strategy = int32_literal(it)
        table_ir(env)(it).map(child => TableRepartition(child, n, strategy))
      case "TableHead" =>
        val n = int64_literal(it)
        table_ir(env)(it).map(child => TableHead(child, n))
      case "TableTail" =>
        val n = int64_literal(it)
        table_ir(env)(it).map(child => TableTail(child, n))
      case "TableJoin" =>
        val joinType = identifier(it)
        val joinKey = int32_literal(it)
        for {
          left <- table_ir(env)(it)
          right <- table_ir(env)(it)
        } yield TableJoin(left, right, joinType, joinKey)
      case "TableLeftJoinRightDistinct" =>
        val root = identifier(it)
        for {
          left <- table_ir(env)(it)
          right <- table_ir(env)(it)
        } yield TableLeftJoinRightDistinct(left, right, root)
      case "TableIntervalJoin" =>
        val root = identifier(it)
        val product = boolean_literal(it)
        for {
          left <- table_ir(env)(it)
          right <- table_ir(env)(it)
        } yield TableIntervalJoin(left, right, root, product)
      case "TableMultiWayZipJoin" =>
        val dataName = string_literal(it)
        val globalsName = string_literal(it)
        table_ir_children(env)(it).map { children =>
          TableMultiWayZipJoin(children, dataName, globalsName)
        }
      case "TableParallelize" =>
        val nPartitions = opt(it, int32_literal)
        ir_value_expr(env)(it).map(rowsAndGlobal => TableParallelize(rowsAndGlobal, nPartitions))
      case "TableMapRows" =>
        for {
          child <- table_ir(env)(it)
          newRow <- ir_value_expr(env)(it)
        } yield TableMapRows(child, newRow)
      case "TableMapGlobals" =>
        for {
          child <- table_ir(env)(it)
          newRow <- ir_value_expr(env)(it)
        } yield TableMapGlobals(child, newRow)
      case "TableRange" =>
        val n = int32_literal(it)
        val nPartitions = opt(it, int32_literal)
        done(TableRange(n, nPartitions.getOrElse(HailContext.backend.defaultParallelism)))
      case "TableUnion" => table_ir_children(env)(it).map(TableUnion(_))
      case "TableOrderBy" =>
        val sortFields = sort_fields(it)
        table_ir(env)(it).map(child => TableOrderBy(child, sortFields))
      case "TableExplode" =>
        val path = string_literals(it)
        table_ir(env)(it).map(child => TableExplode(child, path))
      case "CastMatrixToTable" =>
        val entriesField = string_literal(it)
        val colsField = string_literal(it)
        matrix_ir(env)(it).map(child => CastMatrixToTable(child, entriesField, colsField))
      case "MatrixToTableApply" =>
        val config = string_literal(it)
        matrix_ir(env)(it).map { child =>
          MatrixToTableApply(child, RelationalFunctions.lookupMatrixToTable(env.ctx, config))
        }
      case "TableToTableApply" =>
        val config = string_literal(it)
        table_ir(env)(it).map { child =>
          TableToTableApply(child, RelationalFunctions.lookupTableToTable(env.ctx, config))
        }
      case "BlockMatrixToTableApply" =>
        val config = string_literal(it)
        for {
          bm <- blockmatrix_ir(env)(it)
          aux <- ir_value_expr(env)(it)
        } yield BlockMatrixToTableApply(
          bm,
          aux,
          RelationalFunctions.lookupBlockMatrixToTable(env.ctx, config),
        )
      case "BlockMatrixToTable" => blockmatrix_ir(env)(it).map(BlockMatrixToTable)
      case "TableRename" =>
        val rowK = string_literals(it)
        val rowV = string_literals(it)
        val globalK = string_literals(it)
        val globalV = string_literals(it)
        table_ir(env)(it).map { child =>
          TableRename(child, rowK.zip(rowV).toMap, globalK.zip(globalV).toMap)
        }

      case "TableGen" =>
        val cname = name(it)
        val gname = name(it)
        val partitioner =
          between(punctuation(_, "("), punctuation(_, ")"), partitioner_literal(env))(it)
        val errorId = int32_literal(it)
        for {
          contexts <- ir_value_expr(env)(it)
          globals <- ir_value_expr(env)(it)
          body <- ir_value_expr(env)(it)
        } yield TableGen(contexts, globals, cname, gname, body, partitioner, errorId)

      case "TableFilterIntervals" =>
        val keyType = type_expr(it)
        val intervals = string_literal(it)
        val keep = boolean_literal(it)
        table_ir(env)(it).map { child =>
          TableFilterIntervals(
            child,
            JSONAnnotationImpex.importAnnotation(
              JsonMethods.parse(intervals),
              TArray(TInterval(keyType)),
              padNulls = false,
            ).asInstanceOf[IndexedSeq[Interval]],
            keep,
          )
        }
      case "TableMapPartitions" =>
        val globalsName = name(it)
        val partitionStreamName = name(it)
        val requestedKey = int32_literal(it)
        val allowedOverlap = int32_literal(it)
        for {
          child <- table_ir(env)(it)
          body <- ir_value_expr(env)(it)
        } yield TableMapPartitions(child, globalsName, partitionStreamName, body, requestedKey,
          allowedOverlap)
      case "RelationalLetTable" =>
        val n = name(it)
        for {
          value <- ir_value_expr(env)(it)
          body <- table_ir(env)(it)
        } yield RelationalLetTable(n, value, body)
      case "JavaTable" =>
        val id = int32_literal(it)
        done(env.irMap(id).asInstanceOf[TableIR])
    }
  }

  def matrix_ir_children(env: IRParserEnvironment)(it: TokenIterator): StackFrame[Array[MatrixIR]] =
    repUntil(it, matrix_ir(env), PunctuationToken(")"))

  def matrix_ir(env: IRParserEnvironment)(it: TokenIterator): StackFrame[MatrixIR] = {
    punctuation(it, "(")
    for {
      ir <- call(matrix_ir_1(env)(it))
      _ = punctuation(it, ")")
    } yield ir
  }

  def matrix_ir_1(env: IRParserEnvironment)(it: TokenIterator): StackFrame[MatrixIR] = {
    identifier(it) match {
      case "MatrixFilterCols" =>
        for {
          child <- matrix_ir(env)(it)
          pred <- ir_value_expr(env)(it)
        } yield MatrixFilterCols(child, pred)
      case "MatrixFilterRows" =>
        for {
          child <- matrix_ir(env)(it)
          pred <- ir_value_expr(env)(it)
        } yield MatrixFilterRows(child, pred)
      case "MatrixFilterEntries" =>
        for {
          child <- matrix_ir(env)(it)
          pred <- ir_value_expr(env)(it)
        } yield MatrixFilterEntries(child, pred)
      case "MatrixMapCols" =>
        val newKey = opt(it, string_literals)
        for {
          child <- matrix_ir(env)(it)
          newCol <- ir_value_expr(env)(it)
        } yield MatrixMapCols(child, newCol, newKey.map(_.toFastSeq))
      case "MatrixKeyRowsBy" =>
        val key = identifiers(it)
        val isSorted = boolean_literal(it)
        matrix_ir(env)(it).map(child => MatrixKeyRowsBy(child, key, isSorted))
      case "MatrixMapRows" =>
        for {
          child <- matrix_ir(env)(it)
          newRow <- ir_value_expr(env)(it)
        } yield MatrixMapRows(child, newRow)
      case "MatrixMapEntries" =>
        for {
          child <- matrix_ir(env)(it)
          newEntry <- ir_value_expr(env)(it)
        } yield MatrixMapEntries(child, newEntry)
      case "MatrixUnionCols" =>
        val joinType = identifier(it)
        for {
          left <- matrix_ir(env)(it)
          right <- matrix_ir(env)(it)
        } yield MatrixUnionCols(left, right, joinType)
      case "MatrixMapGlobals" =>
        for {
          child <- matrix_ir(env)(it)
          newGlobals <- ir_value_expr(env)(it)
        } yield MatrixMapGlobals(child, newGlobals)
      case "MatrixAggregateColsByKey" =>
        for {
          child <- matrix_ir(env)(it)
          entryExpr <- ir_value_expr(env)(it)
          colExpr <- ir_value_expr(env)(it)
        } yield MatrixAggregateColsByKey(child, entryExpr, colExpr)
      case "MatrixAggregateRowsByKey" =>
        for {
          child <- matrix_ir(env)(it)
          entryExpr <- ir_value_expr(env)(it)
          rowExpr <- ir_value_expr(env)(it)
        } yield MatrixAggregateRowsByKey(child, entryExpr, rowExpr)
      case "MatrixRead" =>
        val requestedTypeRaw = it.head match {
          case x: IdentifierToken
              if x.value == "None" || x.value == "DropColUIDs" || x.value == "DropRowUIDs" || x.value == "DropRowColUIDs" =>
            consumeToken(it)
            Left(x.value)
          case _ =>
            Right(matrix_type_expr(it))
        }
        val dropCols = boolean_literal(it)
        val dropRows = boolean_literal(it)
        val readerStr = string_literal(it)
        val reader = MatrixReader.fromJson(env, JsonMethods.parse(readerStr).asInstanceOf[JObject])
        val fullType = reader.fullMatrixType
        val requestedType = requestedTypeRaw match {
          case Left("None") => fullType
          case Left("DropRowUIDs") => fullType.copy(
              rowType = fullType.rowType.deleteKey(reader.rowUIDFieldName)
            )
          case Left("DropColUIDs") => fullType.copy(
              colType = fullType.colType.deleteKey(reader.colUIDFieldName)
            )
          case Left("DropRowColUIDs") => fullType.copy(
              rowType = fullType.rowType.deleteKey(reader.rowUIDFieldName),
              colType = fullType.colType.deleteKey(reader.colUIDFieldName),
            )
          case Right(t) => t
        }
        done(MatrixRead(requestedType, dropCols, dropRows, reader))
      case "MatrixAnnotateRowsTable" =>
        val root = string_literal(it)
        val product = boolean_literal(it)
        for {
          child <- matrix_ir(env)(it)
          table <- table_ir(env)(it)
        } yield MatrixAnnotateRowsTable(child, table, root, product)
      case "MatrixAnnotateColsTable" =>
        val root = string_literal(it)
        for {
          child <- matrix_ir(env)(it)
          table <- table_ir(env)(it)
        } yield MatrixAnnotateColsTable(child, table, root)
      case "MatrixExplodeRows" =>
        val path = identifiers(it)
        matrix_ir(env)(it).map(child => MatrixExplodeRows(child, path))
      case "MatrixExplodeCols" =>
        val path = identifiers(it)
        matrix_ir(env)(it).map(child => MatrixExplodeCols(child, path))
      case "MatrixChooseCols" =>
        val oldIndices = int32_literals(it)
        matrix_ir(env)(it).map(child => MatrixChooseCols(child, oldIndices))
      case "MatrixCollectColsByKey" =>
        matrix_ir(env)(it).map(MatrixCollectColsByKey)
      case "MatrixRepartition" =>
        val n = int32_literal(it)
        val strategy = int32_literal(it)
        matrix_ir(env)(it).map(child => MatrixRepartition(child, n, strategy))
      case "MatrixUnionRows" => matrix_ir_children(env)(it).map(MatrixUnionRows(_))
      case "MatrixDistinctByRow" => matrix_ir(env)(it).map(MatrixDistinctByRow)
      case "MatrixRowsHead" =>
        val n = int64_literal(it)
        matrix_ir(env)(it).map(child => MatrixRowsHead(child, n))
      case "MatrixColsHead" =>
        val n = int32_literal(it)
        matrix_ir(env)(it).map(child => MatrixColsHead(child, n))
      case "MatrixRowsTail" =>
        val n = int64_literal(it)
        matrix_ir(env)(it).map(child => MatrixRowsTail(child, n))
      case "MatrixColsTail" =>
        val n = int32_literal(it)
        matrix_ir(env)(it).map(child => MatrixColsTail(child, n))
      case "CastTableToMatrix" =>
        val entriesField = identifier(it)
        val colsField = identifier(it)
        val colKey = identifiers(it)
        table_ir(env)(it).map(child => CastTableToMatrix(child, entriesField, colsField, colKey))
      case "MatrixToMatrixApply" =>
        val config = string_literal(it)
        matrix_ir(env)(it).map { child =>
          MatrixToMatrixApply(child, RelationalFunctions.lookupMatrixToMatrix(env.ctx, config))
        }
      case "MatrixRename" =>
        val globalK = string_literals(it)
        val globalV = string_literals(it)
        val colK = string_literals(it)
        val colV = string_literals(it)
        val rowK = string_literals(it)
        val rowV = string_literals(it)
        val entryK = string_literals(it)
        val entryV = string_literals(it)
        matrix_ir(env)(it).map { child =>
          MatrixRename(
            child,
            globalK.zip(globalV).toMap,
            colK.zip(colV).toMap,
            rowK.zip(rowV).toMap,
            entryK.zip(entryV).toMap,
          )
        }
      case "MatrixFilterIntervals" =>
        val keyType = type_expr(it)
        val intervals = string_literal(it)
        val keep = boolean_literal(it)
        matrix_ir(env)(it).map { child =>
          MatrixFilterIntervals(
            child,
            JSONAnnotationImpex.importAnnotation(
              JsonMethods.parse(intervals),
              TArray(TInterval(keyType)),
              padNulls = false,
            ).asInstanceOf[IndexedSeq[Interval]],
            keep,
          )
        }
      case "RelationalLetMatrixTable" =>
        val n = name(it)
        for {
          value <- ir_value_expr(env)(it)
          body <- matrix_ir(env)(it)
        } yield RelationalLetMatrixTable(n, value, body)
    }
  }

  def blockmatrix_sparsifier(env: IRParserEnvironment)(it: TokenIterator)
    : StackFrame[BlockMatrixSparsifier] = {
    punctuation(it, "(")
    identifier(it) match {
      case "PyRowIntervalSparsifier" =>
        val blocksOnly = boolean_literal(it)
        punctuation(it, ")")
        ir_value_expr(env)(it).map { ir_ =>
          val ir = annotateTypes(env.ctx, ir_, BindingEnv.empty).asInstanceOf[IR]
          val Row(starts: IndexedSeq[Long @unchecked], stops: IndexedSeq[Long @unchecked]) =
            CompileAndEvaluate[Row](env.ctx, ir)
          RowIntervalSparsifier(blocksOnly, starts, stops)
        }
      case "PyBandSparsifier" =>
        val blocksOnly = boolean_literal(it)
        punctuation(it, ")")
        ir_value_expr(env)(it).map { ir_ =>
          val ir = annotateTypes(env.ctx, ir_, BindingEnv.empty).asInstanceOf[IR]
          val Row(l: Long, u: Long) = CompileAndEvaluate[Row](env.ctx, ir)
          BandSparsifier(blocksOnly, l, u)
        }
      case "PyPerBlockSparsifier" =>
        punctuation(it, ")")
        ir_value_expr(env)(it).map { ir_ =>
          val ir = annotateTypes(env.ctx, ir_, BindingEnv.empty).asInstanceOf[IR]
          val indices: IndexedSeq[Int] =
            CompileAndEvaluate[IndexedSeq[Int]](env.ctx, ir)
          PerBlockSparsifier(indices)
        }
      case "PyRectangleSparsifier" =>
        punctuation(it, ")")
        ir_value_expr(env)(it).map { ir_ =>
          val ir = annotateTypes(env.ctx, ir_, BindingEnv.empty).asInstanceOf[IR]
          val rectangles: IndexedSeq[Long] =
            CompileAndEvaluate[IndexedSeq[Long]](env.ctx, ir)
          RectangleSparsifier(rectangles.grouped(4).toIndexedSeq)
        }
      case "RowIntervalSparsifier" =>
        val blocksOnly = boolean_literal(it)
        val starts = int64_literals(it)
        val stops = int64_literals(it)
        punctuation(it, ")")
        done(RowIntervalSparsifier(blocksOnly, starts, stops))
      case "BandSparsifier" =>
        val blocksOnly = boolean_literal(it)
        val l = int64_literal(it)
        val u = int64_literal(it)
        punctuation(it, ")")
        done(BandSparsifier(blocksOnly, l, u))
      case "RectangleSparsifier" =>
        val rectangles = int64_literals(it).toFastSeq
        punctuation(it, ")")
        done(RectangleSparsifier(rectangles.grouped(4).toIndexedSeq))
    }
  }

  def blockmatrix_ir(env: IRParserEnvironment)(it: TokenIterator): StackFrame[BlockMatrixIR] = {
    punctuation(it, "(")
    for {
      ir <- call(blockmatrix_ir1(env)(it))
      _ = punctuation(it, ")")
    } yield ir
  }

  def blockmatrix_ir1(env: IRParserEnvironment)(it: TokenIterator): StackFrame[BlockMatrixIR] = {
    identifier(it) match {
      case "BlockMatrixRead" =>
        val readerStr = string_literal(it)
        val reader = BlockMatrixReader.fromJValue(env.ctx, JsonMethods.parse(readerStr))
        done(BlockMatrixRead(reader))
      case "BlockMatrixMap" =>
        val n = name(it)
        val needs_dense = boolean_literal(it)
        for {
          child <- blockmatrix_ir(env)(it)
          f <- ir_value_expr(env)(it)
        } yield BlockMatrixMap(child, n, f, needs_dense)
      case "BlockMatrixMap2" =>
        val lName = name(it)
        val rName = name(it)
        val sparsityStrategy = SparsityStrategy.fromString(identifier(it))
        for {
          left <- blockmatrix_ir(env)(it)
          right <- blockmatrix_ir(env)(it)
          f <- ir_value_expr(env)(it)
        } yield BlockMatrixMap2(left, right, lName, rName, f, sparsityStrategy)
      case "BlockMatrixDot" =>
        for {
          left <- blockmatrix_ir(env)(it)
          right <- blockmatrix_ir(env)(it)
        } yield BlockMatrixDot(left, right)
      case "BlockMatrixBroadcast" =>
        val inIndexExpr = int32_literals(it)
        val shape = int64_literals(it)
        val blockSize = int32_literal(it)
        blockmatrix_ir(env)(it).map { child =>
          BlockMatrixBroadcast(child, inIndexExpr, shape, blockSize)
        }
      case "BlockMatrixAgg" =>
        val outIndexExpr = int32_literals(it)
        blockmatrix_ir(env)(it).map(child => BlockMatrixAgg(child, outIndexExpr))
      case "BlockMatrixFilter" =>
        val indices = literals(literals(int64_literal))(it)
        blockmatrix_ir(env)(it).map(child => BlockMatrixFilter(child, indices))
      case "BlockMatrixDensify" =>
        blockmatrix_ir(env)(it).map(BlockMatrixDensify)
      case "BlockMatrixSparsify" =>
        for {
          sparsifier <- blockmatrix_sparsifier(env)(it)
          child <- blockmatrix_ir(env)(it)
        } yield BlockMatrixSparsify(child, sparsifier)
      case "BlockMatrixSlice" =>
        val slices = literals(literals(int64_literal))(it)
        blockmatrix_ir(env)(it).map { child =>
          BlockMatrixSlice(child, slices.map(_.toFastSeq).toFastSeq)
        }
      case "ValueToBlockMatrix" =>
        val shape = int64_literals(it)
        val blockSize = int32_literal(it)
        ir_value_expr(env)(it).map(child => ValueToBlockMatrix(child, shape, blockSize))
      case "BlockMatrixRandom" =>
        val staticUID = int64_literal(it)
        val gaussian = boolean_literal(it)
        val shape = int64_literals(it)
        val blockSize = int32_literal(it)
        done(BlockMatrixRandom(staticUID, gaussian, shape, blockSize))
      case "RelationalLetBlockMatrix" =>
        val n = name(it)
        for {
          value <- ir_value_expr(env)(it)
          body <- blockmatrix_ir(env)(it)
        } yield RelationalLetBlockMatrix(n, value, body)
    }
  }

  def annotateTypes(ctx: ExecuteContext, ir: BaseIR, env: BindingEnv[Type]): BaseIR = {
    def run(ir: BaseIR, env: BindingEnv[Type]): BaseIR = {
      val rw = ir.mapChildrenWithEnv(env)(run)
      rw match {
        case x: Ref =>
          x._typ = env.eval(x.name)
          x
        case x: Recur =>
          val TTuple(IndexedSeq(_, TupleField(_, rt))) = env.eval.lookup(x.name)
          x._typ = rt
          x
        case x: ApplyAggOp =>
          x.aggSig.initOpArgs = x.initOpArgs.map(_.typ)
          x.aggSig.seqOpArgs = x.seqOpArgs.map(_.typ)
          x
        case x: ApplyScanOp =>
          x.aggSig.initOpArgs = x.initOpArgs.map(_.typ)
          x.aggSig.seqOpArgs = x.seqOpArgs.map(_.typ)
          x
        case x: ApplyComparisonOp =>
          x.op = x.op.copy(x.l.typ, x.r.typ)
          x
        case MakeArray(args, typ) =>
          MakeArray.unify(ctx, args, typ)
        case x @ InitOp(
              _,
              _,
              BasicPhysicalAggSig(_, FoldStateSig(t, accumName, otherAccumName, combIR)),
            ) =>
          run(
            combIR,
            BindingEnv.empty.bindEval(accumName -> t.virtualType, otherAccumName -> t.virtualType),
          )
          x
        case x @ SeqOp(
              _,
              _,
              BasicPhysicalAggSig(_, FoldStateSig(t, accumName, otherAccumName, combIR)),
            ) =>
          run(
            combIR,
            BindingEnv.empty.bindEval(accumName -> t.virtualType, otherAccumName -> t.virtualType),
          )
          x
        case x @ CombOp(
              _,
              _,
              BasicPhysicalAggSig(_, FoldStateSig(t, accumName, otherAccumName, combIR)),
            ) =>
          run(
            combIR,
            BindingEnv.empty.bindEval(accumName -> t.virtualType, otherAccumName -> t.virtualType),
          )
          x
        case x @ ResultOp(
              _,
              BasicPhysicalAggSig(_, FoldStateSig(t, accumName, otherAccumName, combIR)),
            ) =>
          run(
            combIR,
            BindingEnv.empty.bindEval(accumName -> t.virtualType, otherAccumName -> t.virtualType),
          )
          x
        case Apply(name, typeArgs, args, rt, errorID) =>
          invoke(name, rt, typeArgs, errorID, args: _*)
        case _ =>
          rw
      }
    }

    run(ir, env)
  }

  def parse[T](s: String, f: (TokenIterator) => T): T = {
    val it = IRLexer.parse(s).toIterator.buffered
    f(it)
  }

  def parse_value_ir(
    s: String,
    env: IRParserEnvironment,
    typeEnv: BindingEnv[Type] = BindingEnv.empty,
  ): IR =
    env.ctx.time {
      var ir = parse(s, ir_value_expr(env)(_).run())
      ir = annotateTypes(env.ctx, ir, typeEnv).asInstanceOf[IR]
      TypeCheck(env.ctx, ir, typeEnv)
      ir
    }

  def parse_value_ir(ctx: ExecuteContext, s: String): IR =
    parse_value_ir(s, IRParserEnvironment(ctx))

  def parse_table_ir(ctx: ExecuteContext, s: String): TableIR =
    parse_table_ir(s, IRParserEnvironment(ctx))

  def parse_table_ir(s: String, env: IRParserEnvironment): TableIR =
    env.ctx.time {
      var ir = parse(s, table_ir(env)(_).run())
      ir = annotateTypes(env.ctx, ir, BindingEnv.empty).asInstanceOf[TableIR]
      TypeCheck(env.ctx, ir)
      ir
    }

  def parse_matrix_ir(s: String, env: IRParserEnvironment): MatrixIR =
    env.ctx.time {
      var ir = parse(s, matrix_ir(env)(_).run())
      ir = annotateTypes(env.ctx, ir, BindingEnv.empty).asInstanceOf[MatrixIR]
      TypeCheck(env.ctx, ir)
      ir
    }

  def parse_matrix_ir(ctx: ExecuteContext, s: String): MatrixIR =
    parse_matrix_ir(s, IRParserEnvironment(ctx))

  def parse_blockmatrix_ir(s: String, env: IRParserEnvironment): BlockMatrixIR =
    env.ctx.time {
      var ir = parse(s, blockmatrix_ir(env)(_).run())
      ir = annotateTypes(env.ctx, ir, BindingEnv.empty).asInstanceOf[BlockMatrixIR]
      TypeCheck(env.ctx, ir)
      ir
    }

  def parse_blockmatrix_ir(ctx: ExecuteContext, s: String): BlockMatrixIR =
    parse_blockmatrix_ir(s, IRParserEnvironment(ctx))

  def parseType(code: String): Type = parse(code, type_expr)

  def parsePType(code: String): PType = parse(code, ptype_expr)

  def parseStructType(code: String): TStruct = tcoerce[TStruct](parse(code, type_expr))

  def parseUnionType(code: String): TUnion = tcoerce[TUnion](parse(code, type_expr))

  def parseRVDType(code: String): RVDType = parse(code, rvd_type_expr)

  def parseTableType(code: String): TableType = parse(code, table_type_expr)

  def parseMatrixType(code: String): MatrixType = parse(code, matrix_type_expr)

  def parseSortField(code: String): SortField = parse(code, sort_field)
}
