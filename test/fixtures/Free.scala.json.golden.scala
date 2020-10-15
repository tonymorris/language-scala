package scalaz

import annotation.tailrec

import Free._

import std.function.{function1Covariant => _, function1CovariantByName, _}

import std.tuple._

object Free extends FreeInstances {
  def reset[A](r: Trampoline[A]): Trampoline[A] =
    {
      val a =
        r.run

      return_(a)
    }

  def return_[S[_], A](value: => A)(implicit S: Applicative[S]): Free[S, A] =
    liftF[S, A](S.point(value))

  def pure[S[_], A](value: A): Free[S, A] =
    point(value)

  def roll[S[_], A](value: S[Free[S, A]]): Free[S, A] =
    liftF(value).flatMap((x) => x)

  private[this] val pointUnitCache: Free[Id.Id, Unit] =
    point[Id.Id, Unit](())

  @inline private def pointUnit[S[_]]: Free[S, Unit] =
    pointUnitCache.asInstanceOf[Free[S, Unit]]

  def suspend[S[_], A](value: => Free[S, A]): Free[S, A] =
    pointUnit.flatMap((_) => value)

  def liftFU[MA](value: => MA)(implicit MA: Unapply[Functor, MA]): Free[MA.M, MA.A] =
    liftF(MA(value))

  def joinF[S[_], A](value: Free[Free[S, *], A]): Free[S, A] =
    value.flatMapSuspension(NaturalTransformation.refl[Free[S, *]])

  def pause: Trampoline[Unit] =
    return_(())

  def produce[A](a: A): Source[A, Unit] =
    liftF[(A, *), Unit]((a, ()))

  def await[A]: Sink[A, A] =
    liftF[(=> A) => *, A]((a) => a)

  def apply[S[_], A](s: S[Free[S, A]]): Free[S, A] =
    roll(s)

  private case class Return[S[_], A] (a: A) extends Free[S, A] {

  }

  private case class Suspend[S[_], A] (a: S[A]) extends Free[S, A] {

  }

  private case class Gosub[S[_], A0, B] (a0: Free[S, A0], f0: A0 => Free[S, B]) extends Free[S, B] {
    type A = A0

    def a: Free[S, A] =
      a0

    def f: A => Free[S, B] =
      f0
  }

  type Trampoline[A] = Free[Function0, A]

  type Source[A, B] = Free[(A, *), B]

  type Sink[A, B] = Free[(=> A) => *, B]

  def liftF[S[_], A](value: S[A]): Free[S, A] =
    Suspend(value)

  def point[S[_], A](value: A): Free[S, A] =
    Return[S, A](value)
}

sealed abstract class Free[S[_], A]  {
  final def map[B](f: A => B): Free[S, B] =
    flatMap((a) => Return(f(a)))

  final def >>=[B](f: A => Free[S, B]): Free[S, B] =
    this flatMap f

  final def flatMap[B](f: A => Free[S, B]): Free[S, B] =
    Gosub(this, f)

  final def fold[B](r: A => B, s: S[Free[S, A]] => B)(implicit S: Functor[S]): B =
    resume.fold(s, r)

  final def resume(implicit S: Functor[S]): S[Free[S, A]] \/ A =
    resumeC.leftMap(_.run)

  @tailrec final def resumeC: Coyoneda[S, Free[S, A]] \/ A =
    this match {
      case Return (a) =>
        \/-(a)
      case Suspend (t) =>
        -\/(Coyoneda(t)(Return(_)))
      case b @ Gosub (_, _) =>
        b.a match {
          case Return (a) =>
            b.f(a).resumeC
          case Suspend (t) =>
            -\/(Coyoneda(t)(b.f))
          case c @ Gosub (_, _) =>
            c.a.flatMap((z) => c.f(z).flatMap(b.f)).resumeC
        }
    }

  final def mapSuspension[T[_]](f: S ~> T): Free[T, A] =
    flatMapSuspension( new (S ~> Free[T, *]) {
      def apply[X](s: S[X]) =
        Suspend(f(s))
    } )

  final def mapFirstSuspension(f: S ~> S): Free[S, A] =
    step match {
      case Suspend (s) =>
        Suspend(f(s))
      case a @ Gosub (_, _) =>
        a.a match {
          case Suspend (s) =>
            Suspend(f(s)).flatMap(a.f)
          case _ =>
            a.a.mapFirstSuspension(f).flatMap(a.f)
        }
      case x =>
        x
    }

  final def flatMapSuspension[T[_]](f: S ~> Free[T, *]): Free[T, A] =
    foldMap[Free[T, *]](f)(freeMonad[T])

  final def zapWith[G[_], B, C](bs: Cofree[G, B])(f: (A, B) => C)(implicit d: Zap[S, G]): C =
    Zap.monadComonadZap.zapWith(this, bs)(f)

  final def zap[G[_], B](fs: Cofree[G, A => B])(implicit d: Zap[S, G]): B =
    zapWith(fs)((a, f) => f(a))

  final def bounce(f: S[Free[S, A]] => Free[S, A])(implicit S: Functor[S]): Free[S, A] =
    resume match {
      case -\/ (s) =>
        f(s)
      case \/- (r) =>
        Return(r)
    }

  final def go(f: S[Free[S, A]] => Free[S, A])(implicit S: Functor[S]): A =
    {
      @tailrec def go2(t: Free[S, A]): A =
        t.resume match {
          case -\/ (s) =>
            go2(f(s))
          case \/- (r) =>
            r
        }

      go2(this)
    }

  final def runM[M[_]](f: S[Free[S, A]] => M[Free[S, A]])(implicit S: Functor[S], M: Monad[M]): M[A] =
    {
      def runM2(t: Free[S, A]): M[A] =
        t.resume match {
          case -\/ (s) =>
            Monad[M].bind(f(s))(runM2)
          case \/- (r) =>
            Monad[M].pure(r)
        }

      runM2(this)
    }

  final def runRecM[M[_]](f: S[Free[S, A]] => M[Free[S, A]])(implicit S: Functor[S], M: Applicative[M], B: BindRec[M]): M[A] =
    {
      B.tailrecM(this)( _.resume match {
        case -\/ (sf) =>
          M.map(f(sf))(\/.left)
        case a @ \/- (_) =>
          M.point(a.coerceLeft)
      } )
    }

  @tailrec final def step: Free[S, A] =
    this match {
      case x @ Gosub (_, _) =>
        x.a match {
          case b @ Gosub (_, _) =>
            b.a.flatMap((a) => b.f(a).flatMap(x.f)).step
          case Return (b) =>
            x.f(b).step
          case _ =>
            x
        }
      case x =>
        x
    }

  @tailrec private[scalaz] final def foldStep[B]( onReturn: A => B
  , onSuspend: S[A] => B
  , onGosub: ~>[( {type l[a] = (S[a], a => Free[S, A])})#l, ( {type l[a] = B})#l] ): B =
    this match {
      case Gosub (fz, f) =>
        fz match {
          case Gosub (fy, g) =>
            fy.flatMap((y) => g(y).flatMap(f)).foldStep(onReturn, onSuspend, onGosub)
          case Suspend (sz) =>
            onGosub((sz, f))
          case Return (z) =>
            f(z).foldStep(onReturn, onSuspend, onGosub)
        }
      case Suspend (sa) =>
        onSuspend(sa)
      case Return (a) =>
        onReturn(a)
    }

  final def foldMap[M[_]](f: S ~> M)(implicit M: Monad[M]): M[A] =
    step match {
      case Return (a) =>
        M.pure(a)
      case Suspend (s) =>
        f(s)
      case a @ Gosub (_, _) =>
        M.bind(a.a foldMap f)((c) => a.f(c) foldMap f)
    }

  final def foldMapRec[M[_]](f: S ~> M)(implicit M: Applicative[M], B: BindRec[M]): M[A] =
    B.tailrecM(this)( {
      _.step match {
        case Return (a) =>
          M.point(\/-(a))
        case Suspend (t) =>
          M.map(f(t))(\/.right)
        case b @ Gosub (_, _) =>
          (b.a: @unchecked) match {
            case Suspend (t) =>
              M.map(f(t))((a) => -\/(b.f(a)))
          }
      }
    } )

  import Id._

  final def foldRight[G[_]](z: Id ~> G)(f: λ[α => S[G[α]]] ~> G)(implicit S: Functor[S]): G[A] =
    this.resume match {
      case -\/ (s) =>
        f(S.map(s)(_.foldRight(z)(f)))
      case \/- (r) =>
        z(r)
    }

  @tailrec final def foldRun[B](b: B)(f: λ[α => (B, S[α])] ~> (B, *)): (B, A) =
    step match {
      case Return (a) =>
        (b, a)
      case Suspend (sa) =>
        f((b, sa))
      case g @ Gosub (_, _) =>
        g.a match {
          case Suspend (sz) =>
            {
              val (b1, z) =
                f((b, sz))

              g.f(z).foldRun(b1)(f)
            }
          case _ =>
            sys.error("Unreachable code: `Gosub` returned from `step` must have `Suspend` on the left")
        }
    }

  final def foldRunM[M[_], B](b: B)(f: λ[α => (B, S[α])] ~> λ[α => M[(B, α)]])(implicit M0: Applicative[M], M1: BindRec[M]): M[(B, A)] =
    M1.tailrecM((b, this))( {
      case (b, fa) =>
        fa.step match {
          case Return (a) =>
            M0.point(\/-((b, a)))
          case Suspend (sa) =>
            M0.map(f((b, sa)))(\/.right)
          case g @ Gosub (_, _) =>
            g.a match {
              case Suspend (sz) =>
                M0.map(f((b, sz)))( {
                  case (b, z) =>
                    -\/((b, g.f(z)))
                } )
              case _ =>
                sys.error("Unreachable code: `Gosub` returned from `step` must have `Suspend` on the left")
            }
        }
    } )

  final def run(implicit ev: Free[S, A] === Trampoline[A]): A =
    ev(this).go(_())

  final def zipWith[B, C](tb: Free[S, B])(f: (A, B) => C): Free[S, C] =
    {
      (step, tb.step) match {
        case (Return (a), Return (b)) =>
          Return(f(a, b))
        case (a @ Suspend (_), Return (b)) =>
          a.flatMap((x) => Return(f(x, b)))
        case (Return (a), b @ Suspend (_)) =>
          b.flatMap((x) => Return(f(a, x)))
        case (a @ Suspend (_), b @ Suspend (_)) =>
          a.flatMap((x) => b.map((y) => f(x, y)))
        case (a @ Gosub (_, _), Return (b)) =>
          a.a.flatMap((x) => a.f(x).map(f(_, b)))
        case (a @ Gosub (_, _), b @ Suspend (_)) =>
          a.a.flatMap((x) => b.flatMap((y) => a.f(x).map(f(_, y))))
        case (a @ Gosub (_, _), b @ Gosub (_, _)) =>
          a.a.zipWith(b.a)((x, y) => a.f(x).zipWith(b.f(y))(f)).flatMap((x) => x)
        case (a, b @ Gosub (_, _)) =>
          a.flatMap((x) => b.a.flatMap((y) => b.f(y).map(f(x, _))))
      }
    }

  def collect[B](implicit ev: Free[S, A] === Source[B, A]): (Vector[B], A) =
    {
      @tailrec def go(c: Source[B, A], v: Vector[B] = Vector()): (Vector[B], A) =
        c.resume match {
          case -\/ ((b, cont)) =>
            go(cont, v :+ b)
          case \/- (r) =>
            (v, r)
        }

      go(ev(this))
    }

  def drive[E, B](sink: Sink[Option[E], B])(implicit ev: Free[S, A] === Source[E, A]): (A, B) =
    {
      @tailrec def go(src: Source[E, A], snk: Sink[Option[E], B]): (A, B) =
        (src.resume, snk.resume) match {
          case (-\/ ((e, c)), -\/ (f)) =>
            go(c, f(Some(e)))
          case (-\/ ((e, c)), \/- (y)) =>
            go(c, Monad[Sink[Option[E], *]].pure(y))
          case (\/- (x), -\/ (f)) =>
            go(Monad[Source[E, *]].pure(x), f(None))
          case (\/- (x), \/- (y)) =>
            (x, y)
        }

      go(ev(this), sink)
    }

  def feed[E](ss: LazyList[E])(implicit ev: Free[S, A] === Sink[E, A]): A =
    {
      @tailrec def go(snk: Sink[E, A], rest: LazyList[E]): A =
        (rest, snk.resume) match {
          case (x #:: (xs), -\/ (f)) =>
            go(f(x), xs)
          case (LazyList (), -\/ (f)) =>
            go(f(sys.error("No more values.")), LazyList.empty)
          case (_, \/- (r)) =>
            r
        }

      go(ev(this), ss)
    }

  def drain[E, B](source: Source[E, B])(implicit ev: Free[S, A] === Sink[E, A]): (A, B) =
    {
      @tailrec def go(src: Source[E, B], snk: Sink[E, A]): (A, B) =
        (src.resume, snk.resume) match {
          case (-\/ ((e, c)), -\/ (f)) =>
            go(c, f(e))
          case (-\/ ((e, c)), \/- (y)) =>
            go(c, Monad[Sink[E, *]].pure(y))
          case (\/- (x), -\/ (f)) =>
            sys.error("Not enough values in source.")
          case (\/- (x), \/- (y)) =>
            (y, x)
        }

      go(source, ev(this))
    }

  def duplicateF: Free[Free[S, *], A] =
    extendF[Free[S, *]](NaturalTransformation.refl[Free[S, *]])

  def extendF[T[_]](f: Free[S, *] ~> T): Free[T, A] =
    mapSuspension( new (S ~> T) {
      def apply[X](x: S[X]) =
        f(liftF(x))
    } )

  def extractF(implicit S: Monad[S]): S[A] =
    foldMap(NaturalTransformation.refl[S])

  def toFreeT: FreeT[S, Id, A] =
    this match {
      case Return (a) =>
        FreeT.point(a)
      case Suspend (a) =>
        FreeT.liftF(a)
      case a @ Gosub (_, _) =>
        a.a.toFreeT.flatMap(a.f.andThen(_.toFreeT))
    }
}

object Trampoline {
  def done[A](a: A): Trampoline[A] =
    Free.pure[Function0, A](a)

  def delay[A](a: => A): Trampoline[A] =
    suspend(done(a))

  def suspend[A](a: => Trampoline[A]): Trampoline[A] =
    Free.suspend(a)
}

sealed abstract class FreeInstances4  {
  implicit val trampolineInstance: Comonad[Trampoline] =
    new Comonad[Trampoline] {
      override def map[A, B](fa: Trampoline[A])(f: A => B) =
        fa map f

      def copoint[A](fa: Trampoline[A]) =
        fa.run

      def cobind[A, B](fa: Trampoline[A])(f: Trampoline[A] => B) =
        return_(f(fa))

      override def cojoin[A](fa: Trampoline[A]) =
        Free.point(fa)
    }
}

sealed abstract class FreeInstances3  extends FreeInstances4 {
  implicit def freeFoldable[F[_]: Foldable]: Foldable[Free[F, *]] =
    new FreeFoldable[F] {
      def F =
        implicitly
    }
}

sealed abstract class FreeInstances2  extends FreeInstances3 {
  implicit def freeFoldable1[F[_]: Foldable1]: Foldable1[Free[F, *]] =
    new FreeFoldable1[F] {
      def F =
        implicitly
    }
}

sealed abstract class FreeInstances1  extends FreeInstances2 {
  implicit def freeTraverse[F[_]: Traverse]: Traverse[Free[F, *]] =
    new FreeTraverse[F] {
      def F =
        implicitly
    }
}

sealed abstract class FreeInstances0  extends FreeInstances1 {
  implicit def freeTraverse1[F[_]: Traverse1]: Traverse1[Free[F, *]] =
    new FreeTraverse1[F] {
      def F =
        implicitly
    }

  implicit def freeSemigroup[S[_], A: Semigroup]: Semigroup[Free[S, A]] =
    Semigroup.liftSemigroup[Free[S, *], A]
}

sealed abstract class FreeInstances  extends FreeInstances0 {
  implicit def freeMonad[S[_]]: Monad[Free[S, *]] with BindRec[Free[S, *]] =
    new Monad[Free[S, *]] with BindRec[Free[S, *]] {
      override def map[A, B](fa: Free[S, A])(f: A => B) =
        fa map f

      def bind[A, B](a: Free[S, A])(f: A => Free[S, B]) =
        a flatMap f

      def point[A](a: => A) =
        Free.point(a)

      def tailrecM[A, B](a: A)(f: A => Free[S, A \/ B]): Free[S, B] =
        f(a).flatMap(_.fold(tailrecM(_)(f), point(_)))
    }

  implicit def freeZip[S[_]](implicit Z: Zip[S]): Zip[Free[S, *]] =
    new Zip[Free[S, *]] {
      override def zip[A, B](aa: => Free[S, A], bb: => Free[S, B]) =
        (aa.resumeC, bb.resumeC) match {
          case (-\/ (a), -\/ (b)) =>
            liftF(Z.zip(a.fi, b.fi)).flatMap((ab) => zip(a.k(ab._1), b.k(ab._2)))
          case (-\/ (a), \/- (b)) =>
            liftF(a.fi).flatMap((i) => a.k(i).map((_, b)))
          case (\/- (a), -\/ (b)) =>
            liftF(b.fi).flatMap((i) => b.k(i).map((a, _)))
          case (\/- (a), \/- (b)) =>
            point((a, b))
        }
    }

  implicit def freeMonoid[S[_], A: Monoid]: Monoid[Free[S, A]] =
    Monoid.liftMonoid[Free[S, *], A]
}

private sealed trait FreeBind[F[_]]  extends Bind[Free[F, *]] {
  override def map[A, B](fa: Free[F, A])(f: A => B) =
    fa map f

  def bind[A, B](a: Free[F, A])(f: A => Free[F, B]) =
    a flatMap f
}

private sealed trait FreeFoldable[F[_]]  extends Foldable[Free[F, *]] {
  def F: Foldable[F]

  override final def foldMap[A, B: Monoid](fa: Free[F, A])(f: A => B): B =
    fa.foldStep( f
    , (fa) => F.foldMap(fa)(f)
    , new ~>[( {type l[a] = (F[a], a => Free[F, A])})#l, ( {type l[a] = B})#l] {
      override def apply[X](a: (F[X], X => Free[F, A])) =
        F.foldMap(a._1)((x) => foldMap(a._2 apply x)(f))
    } )

  override final def foldLeft[A, B](fa: Free[F, A], z: B)(f: (B, A) => B): B =
    fa.foldStep( (a) => f(z, a)
    , (fa) => F.foldLeft(fa, z)(f)
    , new ~>[( {type l[a] = (F[a], a => Free[F, A])})#l, ( {type l[a] = B})#l] {
      override def apply[X](a: (F[X], X => Free[F, A])) =
        F.foldLeft(a._1, z)((b, x) => foldLeft(a._2 apply x, b)(f))
    } )

  override final def foldRight[A, B](fa: Free[F, A], z: => B)(f: (A, => B) => B): B =
    fa.foldStep( (a) => f(a, z)
    , (fa) => F.foldRight(fa, z)(f)
    , new ~>[( {type l[a] = (F[a], a => Free[F, A])})#l, ( {type l[a] = B})#l] {
      override def apply[X](a: (F[X], X => Free[F, A])) =
        F.foldRight(a._1, z)((x, b) => foldRight(a._2 apply x, b)(f))
    } )
}

private sealed trait FreeFoldable1[F[_]]  extends Foldable1[Free[F, *]] {
  def F: Foldable1[F]

  override final def foldMap1[A, B: Semigroup](fa: Free[F, A])(f: A => B): B =
    fa.foldStep( f
    , (fa) => F.foldMap1(fa)(f)
    , new ~>[( {type l[a] = (F[a], a => Free[F, A])})#l, ( {type l[a] = B})#l] {
      override def apply[X](a: (F[X], X => Free[F, A])) =
        F.foldMap1(a._1)((x) => foldMap1(a._2 apply x)(f))
    } )

  override final def foldMapRight1[A, B](fa: Free[F, A])(z: A => B)(f: (A, => B) => B): B =
    fa.foldStep( z
    , (fa) => F.foldMapRight1(fa)(z)(f)
    , new ~>[( {type l[a] = (F[a], a => Free[F, A])})#l, ( {type l[a] = B})#l] {
      override def apply[X](a: (F[X], X => Free[F, A])) =
        F.foldMapRight1(a._1)((x) => foldMapRight1(a._2 apply x)(z)(f))((x, b) => foldRight(a._2 apply x, b)(f))
    } )

  override final def foldMapLeft1[A, B](fa: Free[F, A])(z: A => B)(f: (B, A) => B): B =
    fa.foldStep( z
    , (fa) => F.foldMapLeft1(fa)(z)(f)
    , new ~>[( {type l[a] = (F[a], a => Free[F, A])})#l, ( {type l[a] = B})#l] {
      override def apply[X](a: (F[X], X => Free[F, A])) =
        F.foldMapLeft1(a._1)((x) => foldMapLeft1(a._2 apply x)(z)(f))((b, x) => foldLeft(a._2 apply x, b)(f))
    } )
}

private sealed trait FreeTraverse[F[_]]  extends Traverse[Free[F, *]] with FreeFoldable[F] {
  implicit def F: Traverse[F]

  override final def map[A, B](fa: Free[F, A])(f: A => B) =
    fa map f

  override final def traverseImpl[G[_], A, B](fa: Free[F, A])(f: A => G[B])(implicit G: Applicative[G]): G[Free[F, B]] =
    fa.resume match {
      case -\/ (s) =>
        G.map(F.traverseImpl(s)(traverseImpl[G, A, B](_)(f)))(roll)
      case \/- (r) =>
        G.map(f(r))(point)
    }
}

private sealed abstract class FreeTraverse1[F[_]]  extends Traverse1[Free[F, *]] with FreeTraverse[F] with FreeFoldable1[F] {
  implicit def F: Traverse1[F]

  override final def traverse1Impl[G[_], A, B](fa: Free[F, A])(f: A => G[B])(implicit G: Apply[G]): G[Free[F, B]] =
    fa.resume match {
      case -\/ (s) =>
        G.map(F.traverse1Impl(s)(traverse1Impl[G, A, B](_)(f)))(roll)
      case \/- (r) =>
        G.map(f(r))(point)
    }
}