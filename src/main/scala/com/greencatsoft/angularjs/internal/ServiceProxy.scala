package com.greencatsoft.angularjs.internal

import scala.reflect.macros.blackbox.Context
import scala.scalajs.js
import scala.scalajs.js.Any.fromString
import scala.scalajs.js.UndefOr
import scala.scalajs.js.UndefOr.{ any2undefOrA, undefOr2ops }

import com.greencatsoft.angularjs.{ Service, inject, injectable }

object ServiceProxy {

  def bind(service: js.Any, target: Service) {
    try {
      service.asInstanceOf[js.Dynamic].controller = target.asInstanceOf[js.Object]
    } catch {
      case _: Throwable =>
    }
  }

  def unbind[A <: Service](service: js.Any): Option[A] = {
    val target: UndefOr[Any] = service.asInstanceOf[js.Dynamic].controller
    target.map(_.asInstanceOf[A]).toOption
  }

  def identifier[A <: Service](c: Context)(implicit tag: c.WeakTypeTag[A]): Option[String] =
    identifierFromType(c)(tag.tpe)

  def identifierFromType(c: Context)(tpe: c.universe.Type): Option[String] = {
    import c.universe._

    def find(hierarchy: List[Symbol]): Option[String] = hierarchy match {
      case head :: tail =>
        val arg = head.typeSignature.typeSymbol.annotations.map(_.tree) collectFirst {
          case a if a.tpe =:= typeOf[injectable] =>
            a.children.tail
        }

        val value = arg collectFirst {
          case List(Literal(Constant(literal: String))) => literal
        }

        value match {
          case Some(s) => Some(s)
          case _ => find(tail)
        }
      case _ =>
        None
    }

    find(tpe.baseClasses)
  }

  def variableDependencies[A <: Service](c: Context)(implicit tag: c.WeakTypeTag[A]): Iterable[(String, c.universe.TypeSymbol, c.universe.MethodSymbol, Seq[c.universe.Type])] = {
    import c.universe._

    //    Should check if @inject annotation is present:
    //
    //    val members = tag.tpe.members filter {
    //      _.annotations.exists(_.tree.tpe =:= typeOf[inject])
    //    } collect {
    //      case m: MethodSymbol if m.isSetter => m
    //    }

    val setters = tag.tpe.members collect {
      case m: MethodSymbol if m.isSetter => m
    }

    val deps = setters map { s =>
      val tpe = s.paramLists.head.head.typeSignature
      val argType = tpe.dealias.typeSymbol.typeSignature
      val typeArgs = tpe.typeArgs
      val name = identifierFromType(c)(tpe.typeSymbol.typeSignature)

      name.toSeq map {
        (_, tpe.typeSymbol.asType, s, typeArgs)
      }
    }

    deps.flatten
  }

  def constantDependencies[A <: Service](c: Context)(implicit tag: c.WeakTypeTag[A]): Iterable[(String, c.universe.TypeSymbol, Seq[c.universe.Type])] = {
    import c.universe._

    val methods = tag.tpe.members collectFirst {
      case m: MethodSymbol if m.isPrimaryConstructor => m
    }

    val ctor = methods getOrElse {
      c.abort(c.enclosingPosition, s"The specified type '${tag.tpe}' does not have a suitable constructor.")
    }

    val arguments = ctor.paramLists.head map { arg =>
      (arg.name.toString, arg.typeSignature.dealias.typeSymbol.asType, arg.typeSignature.typeArgs)
    }

    val members = arguments map {
      case (n, tpe, typeArgs) =>
        val name = identifierFromType(c)(tpe.typeSignature) getOrElse n

        (name, tpe.asType, typeArgs)
    }

    members.toSeq
  }

  def newObjectWrapper[A <: Service](c: Context)(target: c.Expr[A])(implicit tag: c.WeakTypeTag[A]): c.Expr[js.Any] =
    newIntance[A](c)(Some(target))

  def newClassWrapper[A <: Service](c: Context)(implicit tag: c.WeakTypeTag[A]): c.Expr[js.Any] =
    newIntance[A](c)(None)

  private def newIntance[A <: Service](c: Context)(target: Option[c.Expr[A]])(implicit tag: c.WeakTypeTag[A]): c.Expr[js.Any] = {
    import c.universe._

    val (instantiation, constDeps, constDepNames) = target match {
      case Some(t) => (q"$t", Nil, Nil)
      case None =>
        val deps = constantDependencies[A](c)
        val depNames = deps collect {
          case (name, _, _) => name
        }

        val args = deps.zipWithIndex collect {
          case ((_, argType, typeArgs), index) =>
            val argument = Ident(TermName(s"a$index"))

            if (argType.typeSignature.takesTypeArgs) {
              q"$argument.asInstanceOf[$argType[..$typeArgs]]"
            } else {
              q"$argument.asInstanceOf[$argType]"
            }
        }

        val expr = Apply(Select(
          New(Ident(tag.tpe.typeSymbol)), termNames.CONSTRUCTOR), args.toList)

        (expr, deps, depNames)
    }

    val varDeps = variableDependencies[A](c)
    val varDepNames = varDeps collect {
      case (name, _, _, _) => name
    }

    val offset = constDeps.size

    val assignments = varDeps.zipWithIndex collect {
      case ((_, argType, setter, typeArgs), index) =>
        val argument = Ident(TermName(s"a${index + offset}"))

        val value = if (argType.typeSignature.takesTypeArgs) {
          q"$argument.asInstanceOf[$argType[..$typeArgs]]"
        } else {
          q"$argument.asInstanceOf[$argType]"
        }

        Apply(Select(q"target", setter.asTerm), List(value))
    }

    val depNames = constDepNames ++ varDepNames

    val proxy = q"""{
      import scala.scalajs.js
      import scala.scalajs.js.UndefOr
      import scala.scalajs.js.JSConverters.JSRichOption

      import com.greencatsoft.angularjs.{ Initializable, Service }
      import com.greencatsoft.angularjs.internal.ServiceProxy

      val handler: js.ThisFunction20[js.Any, js.Any, js.Any, js.Any, js.Any, js.Any, js.Any, js.Any, js.Any, js.Any, js.Any, 
        js.Any, js.Any, js.Any, js.Any, js.Any, js.Any, js.Any, js.Any, js.Any, js.Any, UndefOr[js.Any]] = 
        (t: js.Any, a0: js.Any, a1: js.Any, a2: js.Any, a3: js.Any, a4: js.Any, a5: js.Any, a6: js.Any, a7: js.Any, a8: js.Any, a9: js.Any, 
        a10: js.Any, a11: js.Any, a12: js.Any, a13: js.Any, a14: js.Any, a15: js.Any, a16: js.Any, a17: js.Any, a18: js.Any, a19: js.Any) => {

        val target: Service = { ..$instantiation }

        ServiceProxy.bind(t, target)

        ..$assignments

        target.initialize()

        val result = target match {
          case configurable: Function0[_] => configurable()
          case _ => null
        }

        Option(result).map(_.asInstanceOf[js.Any]).orUndefined
      }

      val proxy = js.Array[js.Any](..$depNames)

      proxy.push(handler)
      proxy
    }"""

    c.Expr[js.Any](proxy)
  }
}