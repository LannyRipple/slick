package scala.slick.ast

import scala.language.implicitConversions
import scala.slick.SlickException
import scala.collection.generic.CanBuildFrom
import scala.reflect.ClassTag
import Util._
import scala.collection.mutable.ArrayBuffer

/** Super-trait for all types */
trait Type {
  /** All children of this Type. */
  def children: Seq[Type]
  /** Apply a transformation to all type children and reconstruct this
    * type with the new children, or return the original object if no
    * child is changed. */
  def mapChildren(f: Type => Type): Type
  def select(sym: Symbol): Type =
    throw new SlickException("No type for symbol "+sym+" found in "+this)
  /** The structural view of this type */
  def structural: Type = this
}

/** An atomic type (i.e. a type which does not contain other types) */
trait AtomicType extends Type {
  final def mapChildren(f: Type => Type): this.type = this
  def children: Seq[Type] = Seq.empty
}

final case class StructType(elements: Seq[(Symbol, Type)]) extends Type {
  override def toString = "{" + elements.iterator.map{ case (s, t) => s + ": " + t }.mkString(", ") + "}"
  lazy val symbolToIndex: Map[Symbol, Int] =
    elements.zipWithIndex.map { case ((sym, _), idx) => (sym, idx) }(collection.breakOut)
  def children: Seq[Type] = elements.map(_._2)
  def mapChildren(f: Type => Type): StructType =
    mapOrNone(elements.map(_._2))(f) match {
      case Some(types2) => StructType((elements, types2).zipped.map((e, t) => (e._1, t)))
      case None => this
    }
  override def select(sym: Symbol) = sym match {
    case ElementSymbol(idx) => elements(idx-1)._2
    case _ => elements.find(x => x._1 == sym).map(_._2).getOrElse(super.select(sym))
  }
}

trait OptionType extends Type {
  override def toString = "Option[" + elementType + "]"
  def elementType: Type
  def children: Seq[Type] = Seq(elementType)
}

object OptionType {
  def apply(tpe: Type): OptionType = new OptionType {
    def elementType = tpe
    def mapChildren(f: Type => Type): OptionType = {
      val e2 = f(elementType)
      if(e2 eq elementType) this
      else OptionType(e2)
    }
  }
}

final case class ProductType(elements: IndexedSeq[Type]) extends Type {
  override def toString = "(" + elements.mkString(", ") + ")"
  def mapChildren(f: Type => Type): ProductType =
    mapOrNone(elements)(f) match {
      case Some(e2) => ProductType(e2)
      case None => this
    }
  override def select(sym: Symbol) = sym match {
    case ElementSymbol(i) if i <= elements.length => elements(i-1)
    case _ => super.select(sym)
  }
  def children: Seq[Type] = elements
  def numberedElements: Iterator[(ElementSymbol, Type)] =
    elements.iterator.zipWithIndex.map { case (t, i) => (new ElementSymbol(i+1), t) }
}

final case class CollectionType(cons: CollectionTypeConstructor, elementType: Type) extends Type {
  override def toString = cons + "[" + elementType + "]"
  def mapChildren(f: Type => Type): CollectionType = {
    val e2 = f(elementType)
    if(e2 eq elementType) this
    else CollectionType(cons, e2)
  }
  def children: Seq[Type] = Seq(elementType)
}

case class CollectionTypeConstructor(dummy: String = "") {
  def canBuildFrom = implicitly[CanBuildFrom[Vector[Any], Any, Vector[Any]]]
  override def toString = "Coll"
}

object CollectionTypeConstructor {
  def default = new CollectionTypeConstructor
}

final class MappedScalaType(val baseType: Type, val mapper: MappedScalaType.Mapper) extends Type {
  override def toString = s"Mapped[$baseType]"
  def mapChildren(f: Type => Type): MappedScalaType = {
    val e2 = f(baseType)
    if(e2 eq baseType) this
    else new MappedScalaType(e2, mapper)
  }
  def children: Seq[Type] = Seq(baseType)
  override def select(sym: Symbol) = baseType.select(sym)
}

object MappedScalaType {
  case class Mapper(toBase: Any => Any, toMapped: Any => Any, fastPath: Option[PartialFunction[Any, Any]])
}

/** The standard type for freshly constructed nodes without an explicit type. */
final case object UnassignedType extends AtomicType

/** The type of a structural view of a NominalType before computing the
  * proper type in the `inferTypes` phase. */
final case class UnassignedStructuralType(sym: TypeSymbol) extends AtomicType

/* A type with a name, as used by tables.
 *
 * Compiler phases which change types may keep their own representation
 * of the structural view but must update the AST at the end of the phase
 * so that all NominalTypes with the same symbol have the same structural
 * view. */
final case class NominalType(sym: TypeSymbol)(val structuralView: Type) extends Type {
  def toShortString = s"NominalType($sym)"
  override def toString = s"$toShortString($structuralView)"
  def withStructuralView(t: Type): NominalType =
    if(t == structuralView) this else copy()(t)
  override def structural: Type = structuralView.structural
  override def select(sym: Symbol): Type = structuralView.select(sym)
  def mapChildren(f: Type => Type): NominalType = {
    val struct2 = f(structuralView)
    if(struct2 eq structuralView) this
    else new NominalType(sym)(struct2)
  }
  def children: Seq[Type] = Seq(structuralView)
  def sourceNominalType: NominalType = structuralView match {
    case n: NominalType => n.sourceNominalType
    case _ => this
  }
}

/** Something that has a type */
trait Typed {
  def tpe: Type
}

object Typed {
  def unapply(t: Typed) = Some(t.tpe)
}

/* A Type that carries a Scala type argument */
trait TypedType[T] extends Type { self =>
  def optionType: OptionTypedType[T] = new OptionTypedType[T] {
    val elementType = self
    def scalaType = new ScalaOptionType[T](self.scalaType)
    def mapChildren(f: Type => Type): OptionTypedType[T] = {
      val e2 = f(elementType)
      if(e2 eq elementType) this
      else e2.asInstanceOf[TypedType[T]].optionType
    }
  }
  def scalaType: ScalaType[T]
}

trait BaseTypedType[T] extends TypedType[T] with AtomicType

trait OptionTypedType[T] extends TypedType[Option[T]] with OptionType {
  val elementType: TypedType[T]
}

/** Mark a TypedType as eligible for numeric operators. */
trait NumericTypedType

object TypedType {
  @inline implicit def typedTypeToOptionTypedType[T](implicit t: TypedType[T]): OptionTypedType[T] = t.optionType
}

class TypeUtil(val tpe: Type) extends AnyVal {
  def asCollectionType: CollectionType = tpe match {
    case c: CollectionType => c
    case _ => throw new SlickException("Expected a collection type, found "+tpe)
  }
  def asOptionType: OptionType = tpe match {
    case o: OptionType => o
    case _ => throw new SlickException("Expected an option type, found "+tpe)
  }

  def foreach[U](f: (Type => U)) {
    def g(n: Type) {
      f(n)
      n.children.foreach(g)
    }
    g(tpe)
  }

  @inline def replace(f: PartialFunction[Type, Type]): Type = TypeUtilOps.replace(tpe, f)
  @inline def collect[T](pf: PartialFunction[Type, T]): Iterable[T] = TypeUtilOps.collect(tpe, pf)
  @inline def collectAll[T](pf: PartialFunction[Type, Seq[T]]): Iterable[T] = collect[Seq[T]](pf).flatten
}

object TypeUtil {
  implicit def typeToTypeUtil(tpe: Type) = new TypeUtil(tpe)

  /* An extractor for node types */
  object :@ {
    def unapply(n: Node) = Some((n, n.nodeType))
  }
}

object TypeUtilOps {
  import TypeUtil.typeToTypeUtil

  def replace(tpe: Type, f: PartialFunction[Type, Type]): Type =
    f.applyOrElse(tpe, ({ case t: Type => t.mapChildren(_.replace(f)) }): PartialFunction[Type, Type])

  def collect[T](tpe: Type, pf: PartialFunction[Type, T]): Iterable[T] = {
    val b = new ArrayBuffer[T]
    tpe.foreach(pf.andThen[Unit]{ case t => b += t }.orElse[Type, Unit]{ case _ => () })
    b
  }
}

trait SymbolScope {
  def + (entry: (Symbol, Type)): SymbolScope
  def get(sym: Symbol): Option[Type]
  def withDefault(f: (Symbol => Type)): SymbolScope
}

object SymbolScope {
  val empty = new DefaultSymbolScope(Map.empty)
}

class DefaultSymbolScope(val m: Map[Symbol, Type]) extends SymbolScope {
  def + (entry: (Symbol, Type)) = new DefaultSymbolScope(m + entry)
  def get(sym: Symbol): Option[Type] = m.get(sym)
  def withDefault(f: (Symbol => Type)) = new DefaultSymbolScope(m.withDefault(f))
}

/** A Slick Type encoding of plain Scala types.
  *
  * This is used by QueryInterpreter and MemoryDriver. Values stored in
  * HeapBackend columns are also expected to use these types.
  *
  * All drivers should support the following types which are used internally
  * by the lifted embedding and the query compiler: Boolean, Char, Int, Long,
  * Null, String. */
trait ScalaType[T] extends TypedType[T] {
  override def optionType: ScalaOptionType[T] = new ScalaOptionType[T](this)
  def nullable: Boolean
  def ordered: Boolean
  def scalaOrderingFor(ord: Ordering): scala.math.Ordering[T]
  final def scalaType = this
}

class ScalaBaseType[T](implicit val tag: ClassTag[T], val ordering: scala.math.Ordering[T]) extends ScalaType[T] with BaseTypedType[T] {
  override def toString = "ScalaType[" + tag.runtimeClass.getName + "]"
  def nullable = false
  def ordered = ordering ne null
  def scalaOrderingFor(ord: Ordering) = {
    if(ordering eq null) throw new SlickException("No ordering defined for "+this)
    val base = if(ord.direction == Ordering.Desc) ordering.reverse else ordering
    val nullsFirst = if(ord.nulls == Ordering.NullsFirst) -1 else 1
    new scala.math.Ordering[T] {
      def compare(x: T, y: T): Int = {
        if((x.asInstanceOf[AnyRef] eq null) && (y.asInstanceOf[AnyRef] eq null)) 0
        else if(x.asInstanceOf[AnyRef] eq null) nullsFirst
        else if(y.asInstanceOf[AnyRef] eq null) -nullsFirst
        else base.compare(x, y)
      }
    }
  }
  override def hashCode = tag.hashCode
  override def equals(o: Any) = o match {
    case t: ScalaBaseType[_] => tag == t.tag
    case _ => false
  }
}

object ScalaBaseType {
  implicit val booleanType = new ScalaBaseType[Boolean]
  implicit val bigDecimalType = new ScalaNumericType[BigDecimal](BigDecimal.apply _)
  implicit val byteType = new ScalaNumericType[Byte](_.toByte)
  implicit val charType = new ScalaBaseType[Char]
  implicit val doubleType = new ScalaNumericType[Double](identity)
  implicit val floatType = new ScalaNumericType[Float](_.toFloat)
  implicit val intType = new ScalaNumericType[Int](_.toInt)
  implicit val longType = new ScalaNumericType[Long](_.toLong)
  implicit val nullType = new ScalaBaseType[Null]
  implicit val shortType = new ScalaNumericType[Short](_.toShort)
  implicit val stringType = new ScalaBaseType[String]

  private[this] val all: Map[ClassTag[_], ScalaBaseType[_]] =
    Seq(booleanType, bigDecimalType, byteType, charType, doubleType,
      floatType, intType, longType, nullType, shortType, stringType).map(s => (s.tag, s)).toMap

  def apply[T](implicit tag: ClassTag[T], ord: scala.math.Ordering[T] = null): ScalaBaseType[T] =
    all.getOrElse(tag, new ScalaBaseType[T]).asInstanceOf[ScalaBaseType[T]]
}

class ScalaNumericType[T](val fromDouble: Double => T)(implicit tag: ClassTag[T], val numeric: Numeric[T])
  extends ScalaBaseType[T]()(tag, numeric) with NumericTypedType {
  def toDouble(v: T) = numeric.toDouble(v)
}

class ScalaOptionType[T](val elementType: ScalaType[T]) extends ScalaType[Option[T]] with OptionTypedType[T] {
  override def toString = "ScalaOptionType[" + elementType + "]"
  def nullable = true
  def ordered = elementType.ordered
  def scalaOrderingFor(ord: Ordering) = {
    val nullsFirst = if(ord.nulls == Ordering.NullsFirst) -1 else 1
    val base = elementType.scalaOrderingFor(ord)
    new scala.math.Ordering[Option[T]] {
      def compare(x: Option[T], y: Option[T]): Int = {
        if(x == None && y == None) 0
        else if(x == None) nullsFirst
        else if(y == None) -nullsFirst
        else base.compare(x.get, y.get)
      }
    }
  }
  def mapChildren(f: Type => Type): ScalaOptionType[T] = {
    val e2 = f(elementType)
    if(e2 eq elementType) this
    else e2.asInstanceOf[ScalaType[T]].optionType
  }
}
