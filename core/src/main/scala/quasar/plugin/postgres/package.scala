/*
 * Copyright 2020 Precog Data
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package quasar.plugin

import slamdata.Predef._

import cats.effect.{Sync, Effect, LiftIO}
import cats.effect.concurrent.Ref
import cats.ApplicativeError
import cats.data.{NonEmptyList, ValidatedNel}
import cats.implicits._

import java.net.URI
import java.time.format.{DateTimeFormatter, DateTimeFormatterBuilder, SignStyle}
import java.time.temporal.ChronoField

import quasar.api.{Column, ColumnType}
import quasar.api.resource._
import quasar.connector.render.RenderConfig
import quasar.lib.jdbc.Slf4sLogHandler

import scala.util.Random

import doobie._
import doobie.implicits._

import fs2.Chunk

import org.slf4s.Logger

package object postgres {

  type Ident = String
  type Table = Ident

  val Redacted: String = "--REDACTED--"

  val PostgresCsvConfig: RenderConfig.Csv = {
    val eraPattern = " G"

    val time =
      DateTimeFormatter.ofPattern("HH:mm:ss.S")

    // mutable builder
    def unsignedDate =
      (new DateTimeFormatterBuilder())
        .appendValue(ChronoField.YEAR_OF_ERA, 4, 19, SignStyle.NEVER)
        .appendPattern("-MM-dd")

    RenderConfig.Csv(
      includeHeader = false,
      includeBom = false,

      offsetDateTimeFormat =
        unsignedDate
          .appendLiteral(' ')
          .append(time)
          .appendPattern("Z" + eraPattern)
          .toFormatter,

      localDateTimeFormat =
        unsignedDate
          .appendLiteral(' ')
          .append(time)
          .appendPattern(eraPattern)
          .toFormatter,

      localDateFormat =
        unsignedDate
          .appendPattern(eraPattern)
          .toFormatter)
  }

  /** Returns a quoted and escaped version of `ident`. */
  def hygienicIdent(ident: Ident): Ident =
    s""""${ident.replace("\"", "\"\"")}""""

  def specifyColumnFragments[F[_]: ApplicativeError[?[_], Throwable]](
    cols: NonEmptyList[Column[ColumnType.Scalar]])
      : F[NonEmptyList[Fragment]] =
    cols.traverse(columnSpec).fold(
      invalid => ApplicativeError[F, Throwable].raiseError(new ColumnTypesNotSupported(invalid)),
      _.pure[F])

  def specifyColumnFragment[F[_]: ApplicativeError[?[_], Throwable]](
    col: Column[ColumnType.Scalar])
      : F[Fragment] =
    columnSpec(col).fold(
      invalid => ApplicativeError[F, Throwable].raiseError(new ColumnTypesNotSupported(invalid)),
      _.pure[F])

  def checkExists(log: Logger)(table: Table, schema: Option[Ident]): ConnectionIO[Option[Int]] =
    (fr0"SELECT count(*) as exists_flag FROM information_schema.tables WHERE table_name ='" ++
      Fragment.const0(table) ++
      fr0"'" ++
      fr0" AND table_schema =" ++
      (schema
        .map(s => fr0"'" ++ Fragment.const0(s) ++ fr0"'")
        .getOrElse(fr0"'public'")))
      .queryWithLogHandler[Int](logHandler(log))
      .option
  /** Returns the JDBC connection string corresponding to the given postgres URI. */
  def jdbcUri(pgUri: URI): String =
    s"jdbc:${pgUri}"

  /** Returns a random alphanumeric string of the specified length. */
  def randomAlphaNum[F[_]: Sync](size: Int): F[String] =
    Sync[F].delay(Random.alphanumeric.take(size).mkString)

  /** Attempts to extract a table name from the given path. */
  def tableFromPath(p: ResourcePath): Option[Table] =
    Some(p) collect {
      case table /: ResourcePath.Root => table
    }
  def recordChunks[F[_]: Sync](total: Ref[F, Long], log: Logger)(c: Chunk[Byte]): F[Unit] =
    total.update(_ + c.size) >> logChunkSize[F](c, log)

  def error[F[_]: Sync](log: Logger)(msg: => String, cause: => Throwable): F[Unit] =
    Sync[F].delay(log.error(msg, cause))

  def debug[F[_]: Sync](log: Logger)(msg: => String): F[Unit] =
    Sync[F].delay(log.debug(msg))

  def trace[F[_]: Sync](log: Logger)(msg: => String): F[Unit] =
    Sync[F].delay(log.trace(msg))

  def logHandler(log: Logger): LogHandler =
    Slf4sLogHandler(log)

  def columnSpec(c: Column[ColumnType.Scalar]):
      ValidatedNel[ColumnType.Scalar, Fragment] =
    pgColumnType(c.tpe).map(Fragment.const(hygienicIdent(c.name)) ++ _)

  def toConnectionIO[F[_]: Effect] = Effect.toIOK[F] andThen LiftIO.liftK[ConnectionIO]
  ////

  private def logChunkSize[F[_]: Sync](c: Chunk[Byte], log: Logger): F[Unit] =
    trace[F](log)(s"Sending ${c.size} bytes")

  private val pgColumnType: ColumnType.Scalar => ValidatedNel[ColumnType.Scalar, Fragment] = {
    case ColumnType.Null => fr0"smallint".validNel
    case ColumnType.Boolean => fr0"boolean".validNel
    case ColumnType.LocalTime => fr0"time".validNel
    case ColumnType.OffsetTime => fr0"time with timezone".validNel
    case ColumnType.LocalDate => fr0"date".validNel
    case t @ ColumnType.OffsetDate => t.invalidNel
    case ColumnType.LocalDateTime => fr0"timestamp".validNel
    case ColumnType.OffsetDateTime => fr0"timestamp with time zone".validNel
    case ColumnType.Interval => fr0"interval".validNel
    case ColumnType.Number => fr0"numeric".validNel
    case ColumnType.String => fr0"text".validNel
  }
}
