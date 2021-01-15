package org.http4s

import scala.reflect.macros.whitebox

trait MediaTypePlaform {

  /** Literal syntax for MediaTypes.  Invalid or non-literal arguments are rejected
    * at compile time.
    */
  @deprecated("""use mediaType"" string interpolation instead""", "0.20")
  def mediaType(s: String): MediaType = macro MediaTypePlaform.Macros.mediaTypeLiteral
}

object MediaTypePlaform {

  private[MediaTypePlaform] class Macros(val c: whitebox.Context) {
    import c.universe._

    def mediaTypeLiteral(s: c.Expr[String]): Tree =
      s.tree match {
        case Literal(Constant(s: String)) =>
          MediaType
            .parse(s)
            .fold(
              e => c.abort(c.enclosingPosition, e.details),
              _ =>
                q"_root_.org.http4s.MediaType.parse($s).fold(throw _, _root_.scala.Predef.identity)"
            )
        case _ =>
          c.abort(
            c.enclosingPosition,
            s"This method uses a macro to verify that a String literal is a valid media type. Use MediaType.parse if you have a dynamic String that you want to parse as a MediaType."
          )
      }
  }
}
