package io.github.karimagnusson.zio.path

import java.net.{URL, URI, HttpURLConnection}
import java.io.IOException
import java.io.InputStream
import java.nio.file.attribute.FileTime
import java.nio.file.{
  Files,
  Paths,
  Path,
  StandardOpenOption
}

import scala.io.{Source, Codec}
import scala.jdk.CollectionConverters._
import zio._
import zio.stream.{ZStream, ZPipeline, ZSink}
import io.github.karimagnusson.zio.path.utils._


case class ZPathInfo(
  path: Path,
  isDir: Boolean,
  isHidden: Boolean,
  isReadable: Boolean,
  isWritable: Boolean,
  isSymbolicLink: Boolean,
  lastModified: FileTime
)


object ZPath {
  
  lazy val root = Paths.get("").toAbsolutePath.toString

  private def toZPath(path: Path): Task[ZPath] = ZIO.attemptBlocking {
    if (Files.isDirectory(path)) ZDir(path) else ZFile(path)
  }

  def fromPath(path: Path): Task[ZPath] =
    toZPath(path)
  
  def rel(parts: String*): Task[ZPath] =
    toZPath(Paths.get(root, parts: _*))
  
  def get(first: String, rest: String*): Task[ZPath] =
    toZPath(Paths.get(first, rest: _*))

  // url stream

  private def readInput(is: InputStream): String =
    Source.fromInputStream(is)(Codec.UTF8).mkString

  def urlStream(url: String): ZStream[Any, IOException, Byte] =
    urlStream(url, Map.empty)

  def urlStream(
    url: String,
    headers: Map[String, String]
  ): ZStream[Any, IOException, Byte] = ZStream.fromInputStreamZIO(
    ZIO.attemptBlocking {
      val javaUrl = new URI(url).toURL()
      val conn = javaUrl.openConnection().asInstanceOf[HttpURLConnection]
      conn.setRequestMethod("GET")
      headers.foreach(h => conn.setRequestProperty(h._1, h._2))
      if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) {
        throw new IOException(readInput(conn.getInputStream()))
      }
      conn.getInputStream()
    }.refineToOrDie[IOException]
  )

  def urlSink(url: String): ZSink[Any, IOException, Byte, Byte, String] =
    urlSink(url, Map.empty)

  def urlSink(
    url: String,
    headers: Map[String, String]
  ): ZSink[Any, IOException, Byte, Byte, String] = ZSink.unwrap {
    ZIO.attemptBlocking {
      val javaUrl = new URI(url).toURL()
      val conn = javaUrl.openConnection().asInstanceOf[HttpURLConnection]
      conn.setDoOutput(true)
      conn.setRequestMethod("POST")
      headers.foreach(h => conn.setRequestProperty(h._1, h._2))
      ZSink
        .fromOutputStream(conn.getOutputStream())
        .mapZIO { _ => 
          ZIO.attemptBlocking(
            readInput(conn.getInputStream())
          ).refineToOrDie[IOException]
        }
        .orElse {
          ZSink.fail(new IOException(
             readInput(conn.getInputStream())
          ))
        }
    }.catchSome {
      case ex: IOException =>
        ZIO.succeed(ZSink.fail(ex))
    }.refineToOrDie[IOException]
  }
}


sealed trait ZPath {
  
  val path: Path
  def name = path.getFileName.toString
  def isFile: Boolean
  def isDir: Boolean
  def show = path.toString
  def startsWithDot = name.head == '.'
  def parent = ZDir(path.getParent)

  def delete: IO[IOException, Unit]
  def copy(dest: ZDir): Task[Unit]
  def copyTo(dest: ZDir): Task[Unit]
  def size: IO[IOException, Long]
  def isEmpty: IO[IOException, Boolean]
  def nonEmpty: IO[IOException, Boolean]

  def exists: UIO[Boolean] = ZIO.attemptBlocking {
    Files.exists(path)
  }.orDie

  def info: IO[IOException, ZPathInfo] = ZIO.attemptBlocking {
    ZPathInfo(
      path,
      Files.isDirectory(path),
      Files.isHidden(path),
      Files.isReadable(path),
      Files.isWritable(path),
      Files.isSymbolicLink(path),
      Files.getLastModifiedTime(path)
    )
  }.refineToOrDie[IOException]

  override def toString = path.toString
}


object ZFile {

  def fromPath(path: Path) =
    ZFile(path)

  def rel(parts: String*) =
    ZFile(Paths.get(ZPath.root, parts: _*))

  def get(first: String, rest: String*) =
    ZFile(Paths.get(first, rest: _*))
  
  def deleteFiles(files: Seq[ZFile]): IO[IOException, Unit] =
    ZIO.attemptBlocking {
      files.foreach(f => Files.deleteIfExists(f.path))
    }.unit.refineToOrDie[IOException]
}


case class ZFile(path: Path) extends ZPath {

  def isFile = true
  def isDir = false

  def ext = name.split('.').lastOption
  def extUpper = ext.map(_.toUpperCase).getOrElse("")
  def extLower = ext.map(_.toLowerCase).getOrElse("")

  def relTo(dir: ZDir) = ZFile(dir.path.relativize(path))

  def assert: IO[IOException, ZFile] = ZIO.attemptBlocking {
    if (!Files.exists(path))
      throw new IOException(s"path does not exist: $path")
    if (!Files.isRegularFile(path))
      throw new IOException(s"path is not a file: $path")
    this
  }.refineToOrDie[IOException]

  def create: Task[ZFile] =
    ZIO.attemptBlocking(Files.createFile(path)).map(_ => this)

  def size: IO[IOException, Long] =
    ZIO.attemptBlocking(Files.size(path)).refineToOrDie[IOException]

  def isEmpty: IO[IOException, Boolean] = size.map(_ == 0)
  def nonEmpty: IO[IOException, Boolean] = size.map(_ > 0)

  def delete: IO[IOException, Unit] = ZIO.attemptBlocking {
    Files.deleteIfExists(path)
  }.unit.refineToOrDie[IOException]

  // read

  def readBytes: IO[IOException, Array[Byte]] = ZIO.attemptBlocking {
    Files.readAllBytes(path)
  }.refineToOrDie[IOException]

  def readString: IO[IOException, String] = for {
    bytes <- readBytes
  } yield bytes.map(_.toChar).mkString

  def readLines: IO[IOException, List[String]] = for {
    content <- readString
  } yield content.split("\n").toList

  // write

  def write(bytes: Array[Byte]): Task[Unit] =
    ZIO.attemptBlocking(Files.write(path, bytes))

  def write(str: String): Task[Unit] =
    write(str.getBytes)

  def write(lines: Seq[String]): Task[Unit] =
    write(lines.mkString("\n").getBytes)

  // append

  def append(bytes: Array[Byte]): Task[Unit] = ZIO.attemptBlocking {
    Files.write(path, bytes, StandardOpenOption.APPEND)
  }

  def append(str: String): Task[Unit] =
    append(str.getBytes)

  def append(lines: Seq[String]): Task[Unit] =
    append(("\n" + lines.mkString("\n")).getBytes)

  // copy

  @deprecated("use copyTo", "2.0.2")
  def copy(target: ZFile): Task[Unit] = copyTo(target)

  @deprecated("use copyTo", "2.0.2")
  def copy(dest: ZDir): Task[Unit] = copyTo(dest.file(name))

  def copyTo(target: ZFile): Task[Unit] = ZIO.attemptBlocking {
    Files.copy(path, target.path)
  }.unit

  def copyTo(dest: ZDir): Task[Unit] = copyTo(dest.file(name))

  // rename

  def rename(target: ZFile): Task[ZFile] = ZIO.attemptBlocking {
    Files.move(path, target.path)
  }.map(_ => target)

  def rename(fileName: String): Task[ZFile] =
    rename(parent.file(fileName))

  def moveTo(dest: ZDir): Task[ZFile] =
    rename(dest.file(name))

  // mime

  def mimeType: IO[IOException, Option[String]] =
    ZIO.attemptBlocking(
      Option(Files.probeContentType(path))
    ).refineToOrDie[IOException]

  // gzip

  def gzip: Task[ZFile] =
    gzip(parent.file(name + ".gz"))

  def gzip(out: ZFile): Task[ZFile] =
    ZStream
      .fromPath(path)
      .via(ZPipeline.gzip())
      .run(ZSink.fromPath(out.path))
      .map(_ => out)

  def ungzip: Task[ZFile] =
    ungzip(parent.file(name.substring(0, name.size - 3)))

  def ungzip(out: ZFile): Task[ZFile] = 
    ZStream
      .fromPath(path)
      .via(ZPipeline.gunzip())
      .run(ZSink.fromPath(out.path))
      .map(_ => out)

  // zip

  def unzip: Task[ZDir] = unzip(parent)

  def unzip(dest: ZDir): Task[ZDir] = ZIO.attemptBlocking {
    Archive.unzip(path, dest.path)
  }.map(_ => dest)

  // untar

  def untar: Task[ZDir] = untar(parent)

  def untar(dest: ZDir): Task[ZDir] = ZIO.attemptBlocking {
    Archive.untar(path, dest.path, false)
  }.map(_ => dest)

  def untarGz: Task[ZDir] = untarGz(parent)

  def untarGz(dest: ZDir): Task[ZDir] = ZIO.attemptBlocking {
    Archive.untar(path, dest.path, true)
  }.map(_ => dest)

  // stream

  @deprecated("use copyTo", "2.0.2")
  def fillFrom(url: URL): Task[Long] = download(url.toString).map(_ => 0L)

  def download(url: String): IO[IOException, ZFile] =
    download(url, Map.empty[String, String])

  def download(url: String, headers: Map[String, String]): IO[IOException, ZFile] =
    ZPath.urlStream(url, headers).run(asSink).map(_ => this).refineToOrDie[IOException]

  def upload(url: String): IO[IOException, String] = upload(url, Map.empty)

  def upload(url: String, headers: Map[String, String]): IO[IOException, String] = for {
    ct  <- mimeType.map(_.getOrElse("application/octet-stream"))
    cs  <- size.map(_.toString)
    ah  <- ZIO.succeed(headers ++ Map("Content-Type" -> ct, "Content-Size" -> cs))
    res <- streamBytes.run(ZPath.urlSink(url, ah))
  } yield res

  def asSink: ZSink[Any, Throwable, Byte, Byte, Long] =
    ZSink.fromPath(path)

  def asStringSink: ZSink[Any, Throwable, String, Byte, Long] =
    asSink.contramapChunks[String](_.flatMap(_.getBytes))

  def streamBytes: ZStream[Any, IOException, Byte] = 
    ZStream.fromPath(path).refineToOrDie[IOException]

  def streamLines: ZStream[Any, IOException, String] =
    streamBytes
      .via(ZPipeline.utf8Decode)
      .via(ZPipeline.splitLines)
}


object ZDir {
  
  def fromPath(path: Path) =
    ZDir(path)

  def rel(parts: String*) =
    ZDir(Paths.get(ZPath.root, parts: _*))

  def get(first: String, rest: String*) =
    ZDir(Paths.get(first, rest: _*))

  def mkdirs(dirNames: Seq[String]): IO[IOException, List[ZDir]] =
    ZIO.attemptBlocking {
      val zDirs = dirNames.map(d => get(d)).toList
      zDirs.foreach(d => Files.createDirectories(d.path))
      zDirs
    }.refineToOrDie[IOException] 
}


case class ZDir(path: Path) extends ZPath {

  private def pickFiles(paths: List[ZPath]): List[ZFile] =
    paths.filter(_.isFile).map(_.asInstanceOf[ZFile])

  private def pickDirs(paths: List[ZPath]): List[ZDir] =
    paths.filter(_.isDir).map(_.asInstanceOf[ZDir])

  private def listDir(p: Path): List[Path] =
    Files.list(p).iterator.asScala.toList

  private def walkDir(p: Path): List[Path] =
    Files.walk(p).iterator.asScala.toList

  private val toZPath: Path => ZPath = { p =>
    if (Files.isDirectory(p)) ZDir(p) else ZFile(p)
  }

  def isFile = false
  def isDir = true

  def relTo(other: ZDir) = ZDir(other.path.relativize(path))

  def add(other: ZFile) = ZFile(path.resolve(other.path))
  def add(other: ZDir) = ZDir(path.resolve(other.path))
  def add(other: ZPath): ZPath = other match {
    case p: ZFile => add(p)
    case p: ZDir  => add(p)
  }

  def file(fileName: String) = add(ZFile.get(fileName))
  def dir(dirName: String) = add(ZDir.get(dirName))

  def assert: IO[IOException, ZDir] =
    ZIO.attemptBlocking {
      if (!Files.exists(path))
        throw new IOException(s"path does not exist: $path")
      if (!Files.isDirectory(path))
        throw new IOException(s"path is not a file: $path")
      this
    }.refineToOrDie[IOException]

  def size: IO[IOException, Long] = ZIO.attemptBlocking {
    walkDir(path).foldLeft(0L) { (acc, p) => acc + Files.size(p) }
  }.refineToOrDie[IOException]

  def isEmpty: IO[IOException, Boolean] = list.map(_.isEmpty)
  def nonEmpty: IO[IOException, Boolean] = list.map(_.nonEmpty)

  def create: Task[ZDir] = 
    ZIO.attemptBlocking(Files.createDirectories(path)).map(_ => this)

  def mkdir(dirName: String): Task[ZDir] =
    dir(dirName).create

  def mkdirs(dirNames: Seq[String]): IO[IOException, List[ZDir]] = (for {
    zDirs <- ZIO.attempt(dirNames.map(dir).toList)
    _ <- ZIO.attemptBlocking(zDirs.map(d => Files.createDirectories(d.path)))
  } yield zDirs).refineToOrDie[IOException]

  def rename(dest: ZDir): Task[ZDir] =
    ZIO.attemptBlocking(Files.move(path, dest.path)).map(_ => dest)

  def rename(dirName: String): Task[ZDir] = rename(parent.dir(dirName))

  def moveTo(dest: ZDir): Task[ZDir] = rename(dest.dir(name))

  def moveHere(paths: Seq[ZPath]): Task[Seq[ZPath]] = ZIO.attemptBlocking {
    paths.foreach(p => Files.move(p.path, path.resolve(p.name)))
    paths.toList
  }.refineToOrDie[IOException]

  // copy

  @deprecated("use copyTo", "2.0.2")
  def copy(other: ZDir): Task[Unit] = copyTo(other)

  def copyTo(other: ZDir): Task[Unit] = ZIO.attemptBlocking {
    def loop(source: ZPath, dest: ZDir): Unit = {
      source match {
        case sourceDir: ZDir =>
          val nextDest = dest.dir(sourceDir.name)
          Files.createDirectory(nextDest.path)
          listDir(sourceDir.path).map(toZPath).foreach { p =>
            loop(sourceDir.add(p), nextDest)
          }
        case sourceFile: ZFile =>
          Files.copy(sourceFile.path, dest.file(sourceFile.name).path)
      }
    }
    loop(this, other)
  }

  // delete

  private def deleteAny(p: Path): Unit = {
    if (Files.isDirectory(p)) {
      listDir(p).foreach(deleteAny)
      Files.deleteIfExists(p)
    } else {
      Files.deleteIfExists(p)
    }
  }

  def delete: IO[IOException, Unit] = ZIO.attemptBlocking {
    if (Files.exists(path))
      deleteAny(path)
  }.refineToOrDie[IOException]

  def empty: IO[IOException, ZDir] = ZIO.attemptBlocking {
    listDir(path).foreach(deleteAny)
  }.map(_ => this).refineToOrDie[IOException]

  // zip

  def zip: Task[ZFile] = zip(parent.file(name + ".zip"))

  def zip(out: ZFile): Task[ZFile] = ZIO.attemptBlocking {
    Archive.zip(path, out.path)
  }.map(_ => out)

  // tar

  def tar: Task[ZFile] = tar(parent)

  def tar(dest: ZDir): Task[ZFile] = for {
    tarFile <- ZIO.attempt(dest.file(name + ".tar"))
    _ <- ZIO.attemptBlocking(Archive.tar(path, tarFile.path, false))
  } yield tarFile

  def tarGz: Task[ZFile] = tarGz(parent)

  def tarGz(dest: ZDir): Task[ZFile] = for {
    tarGzFile <- ZIO.attempt(dest.file(name + ".tar.gz"))
    _ <- ZIO.attemptBlocking(Archive.tar(path, tarGzFile.path, true))
  } yield tarGzFile

  // list

  def list: IO[IOException, List[ZPath]] = ZIO.attemptBlocking {
    listDir(path).map(toZPath)
  }.refineToOrDie[IOException]

  def listFiles: IO[IOException, List[ZFile]] =
    list.map(pickFiles(_))

  def listDirs: IO[IOException, List[ZDir]] =
    list.map(pickDirs(_))

  // walk

  def walk: IO[IOException, List[ZPath]] = ZIO.attemptBlocking {
    walkDir(path).map(toZPath)
  }.refineToOrDie[IOException]

  def walkFiles: IO[IOException, List[ZFile]] =
    walk.map(pickFiles(_))

  def walkDirs: IO[IOException, List[ZDir]] =
    walk.map(pickDirs(_))

  def streamWalk: ZStream[Any, Throwable, ZPath] =
    ZStream.unfoldChunkZIO(new WalkIter(path))(_.next)

  def streamWalkFiles: ZStream[Any, Throwable, ZFile] =
    streamWalk.filter(_.isFile).map(_.asInstanceOf[ZFile])

  def streamWalkDirs: ZStream[Any, Throwable, ZDir] =
    streamWalk.filter(_.isDir).map(_.asInstanceOf[ZDir])

  private class WalkIter(path: Path) {

    var iterator: Option[Iterator[Path]] = None

    def iter = iterator match {
      case Some(iter) => ZIO.succeed(iter)
      case None => for {
        iter  <- ZIO.attemptBlocking(Files.walk(path).iterator.asScala)
        _     <- ZIO.succeed { iterator = Some(iter) }
      } yield iter
    }

    def take(iter: Iterator[Path]) = ZIO.attemptBlocking {
      iter.take(100).toList.map(toZPath)
    }
      
    val toChunk: List[ZPath] => Option[(Chunk[ZPath], WalkIter)] = {
      case Nil => None
      case batch => Some((Chunk.fromIterable(batch), this))
    }

    def next = iter.flatMap(take).map(toChunk)
  }
}


















