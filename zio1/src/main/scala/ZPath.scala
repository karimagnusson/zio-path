package io.github.karimagnusson.zio.path

import java.net.URL
import java.util.Comparator
import java.io.IOException
import java.nio.file.attribute.FileTime
import java.nio.file.{
  Files,
  Paths,
  Path,
  StandardOpenOption
}

import scala.jdk.CollectionConverters._

import zio._
import zio.blocking._
import zio.stream.{ZStream, ZTransducer, ZSink}


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

  private def toZPath(path: Path): RIO[Blocking, ZPath] = effectBlocking {
    if (Files.isDirectory(path)) ZDir(path) else ZFile(path)
  }

  def fromPath(path: Path) = toZPath(path)
  def rel(parts: String*) = toZPath(Paths.get(root, parts: _*))
  def get(first: String, rest: String*) = toZPath(Paths.get(first, rest: _*))

  def pickFiles(paths: List[ZPath]): List[ZFile] =
    paths.filter(_.isFile).map(_.asInstanceOf[ZFile])

  def pickDirs(paths: List[ZPath]): List[ZDir] =
    paths.filter(_.isDir).map(_.asInstanceOf[ZDir])
}


sealed trait ZPath {
  
  val path: Path
  def delete: ZIO[Blocking, IOException, Unit]
  def copy(dest: ZDir): RIO[Blocking, Unit]
  def size: ZIO[Blocking, IOException, Long]
  def isEmpty: ZIO[Blocking, IOException, Boolean]
  def nonEmpty: ZIO[Blocking, IOException, Boolean]
  def isFile: Boolean
  def isDir: Boolean

  def name = path.getFileName.toString
  def show = path.toString
  def startsWithDot = name.head == '.'
  def parent = ZDir(path.getParent)

  def exists: URIO[Blocking, Boolean] = effectBlocking {
    Files.exists(path)
  }.orDie

  def info: ZIO[Blocking, IOException, ZPathInfo] = effectBlocking {
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

  def fromPath(path: Path) = ZFile(path)
  def rel(parts: String*) = ZFile(Paths.get(ZPath.root, parts: _*))
  def get(first: String, rest: String*) = ZFile(Paths.get(first, rest: _*))
  
  def deleteFiles(files: Seq[ZFile]): ZIO[Blocking, IOException, Unit] = effectBlocking {
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

  private def assertFile: ZFile = {
    if (!Files.exists(path))
      throw new IOException(s"path does not exist: $path")
    if (!Files.isRegularFile(path))
      throw new IOException(s"path is not a file: $path")
    this
  } 

  def assert: ZIO[Blocking, IOException, ZFile] =
    effectBlocking(assertFile).refineToOrDie[IOException]

  def create: RIO[Blocking, ZFile] = for {
    _ <- effectBlocking(Files.createFile(path))
  } yield this

  def size: ZIO[Blocking, IOException, Long] =
    effectBlocking(Files.size(path)).refineToOrDie[IOException]

  def isEmpty: ZIO[Blocking, IOException, Boolean] = size.map(_ == 0)
  def nonEmpty: ZIO[Blocking, IOException, Boolean] = size.map(_ > 0)

  def delete: ZIO[Blocking, IOException, Unit] = effectBlocking {
    Files.deleteIfExists(path)
  }.unit.refineToOrDie[IOException]

  // read

  def readBytes: ZIO[Blocking, IOException, Array[Byte]] = effectBlocking {
    Files.readAllBytes(path)
  }.refineToOrDie[IOException]

  def readString: ZIO[Blocking, IOException, String] = for {
    bytes <- readBytes
  } yield bytes.map(_.toChar).mkString

  def readLines: ZIO[Blocking, IOException, List[String]] = for {
    content <- readString
  } yield content.split("\n").toList

  // write

  def write(bytes: Array[Byte]): RIO[Blocking, Unit] =
    effectBlocking(Files.write(path, bytes))

  def write(str: String): RIO[Blocking, Unit] =
    write(str.getBytes)

  def write(lines: Seq[String]): RIO[Blocking, Unit] =
    write(lines.mkString("\n").getBytes)

  // append

  def append(bytes: Array[Byte]): RIO[Blocking, Unit] = effectBlocking {
    Files.write(path, bytes, StandardOpenOption.APPEND)
  }

  def append(str: String): RIO[Blocking, Unit] =
    append(str.getBytes)

  def append(lines: Seq[String]): RIO[Blocking, Unit] =
    append(("\n" + lines.mkString("\n")).getBytes)

  // copy

  def copy(target: ZFile): RIO[Blocking, Unit] = effectBlocking {
    Files.copy(path, target.path)
  }

  def copy(dest: ZDir): RIO[Blocking, Unit] = copy(dest.file(name))

  // rename

  def rename(target: ZFile): RIO[Blocking, ZFile] = for {
    _ <- effectBlocking(Files.move(path, target.path))
  } yield target

  def rename(fileName: String): RIO[Blocking, ZFile] = rename(parent.file(fileName))

  def moveTo(dest: ZDir): RIO[Blocking, ZFile] = rename(dest.file(name))

  

  // stream

  def fillFrom(url: URL): RIO[Blocking, Long] = 
    ZStream.fromInputStreamEffect(
      effectBlocking(url.openStream).refineToOrDie[IOException]
    ).run(asSink)

  def asSink: ZSink[Blocking, Throwable, Byte, Byte, Long] =
    ZSink.fromFile(path)

  def asStringSink: ZSink[Blocking, Throwable, String, Byte, Long] =
    asSink.contramapChunks[String](_.flatMap(_.getBytes))

  def streamBytes: ZStream[Blocking, Throwable, Byte] = 
    ZStream.fromFile(path)

  def streamLines: ZStream[Blocking, Throwable, String] =
    streamBytes
      .transduce(ZTransducer.utf8Decode >>> ZTransducer.splitLines)
}


object ZDir {
  def fromPath(path: Path) = ZDir(path)
  def rel(parts: String*) = ZDir(Paths.get(ZPath.root, parts: _*))
  def get(first: String, rest: String*) = ZDir(Paths.get(first, rest: _*))

  def mkdirs(dirs: Seq[ZDir]): ZIO[Blocking, IOException, Seq[ZDir]] = (for {
    _ <- effectBlocking {
      dirs
        .map(_.path)
        .filterNot(Files.exists(_))
        .foreach(Files.createDirectory(_))
    }
  } yield dirs).refineToOrDie[IOException] 
}


case class ZDir(path: Path) extends ZPath {

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

  def add(other: ZPath): ZPath = other match {
    case p: ZFile => add(p)
    case p: ZDir  => add(p)
  }

  def add(other: ZFile) = ZFile(path.resolve(other.path))

  def add(other: ZDir) = ZDir(path.resolve(other.path))

  def file(fileName: String) = add(ZFile.get(fileName))

  def dir(dirName: String) = add(ZDir.get(dirName))

  private def assertDir: ZDir = {
    if (!Files.exists(path))
      throw new IOException(s"path does not exist: $path")
    if (!Files.isDirectory(path))
      throw new IOException(s"path is not a file: $path")
    this
  } 

  def assert: ZIO[Blocking, IOException, ZDir] =
    effectBlocking(assertDir).refineToOrDie[IOException]


  def size: ZIO[Blocking, IOException, Long] = effectBlocking {
    walkDir(path).foldLeft(0L) { (acc, p) => acc + Files.size(p) }
  }.refineToOrDie[IOException]

  def isEmpty: ZIO[Blocking, IOException, Boolean] = list.map(_.isEmpty)
  def nonEmpty: ZIO[Blocking, IOException, Boolean] = list.map(_.nonEmpty)

  def create: RIO[Blocking, ZDir] = for {
    _ <- effectBlocking(Files.createDirectories(path))
  } yield this

  def mkdir(dirName: String): RIO[Blocking, ZDir] =
    dir(dirName).create

  def mkdirs(dirNames: Seq[String]): ZIO[Blocking, IOException, Seq[ZDir]] =
    ZDir.mkdirs(dirNames.map(dir))

  def rename(dest: ZDir): RIO[Blocking, ZDir] = for {
    _ <- effectBlocking(Files.move(path, dest.path))
  } yield dest

  def rename(dirName: String): RIO[Blocking, ZDir] = rename(parent.dir(dirName))

  def moveTo(dest: ZDir): RIO[Blocking, ZDir] = rename(dest.dir(name))

  def moveHere(paths: Seq[ZPath]): RIO[Blocking, Seq[ZPath]] = effectBlocking {
    paths.map {
      case f: ZFile => ZFile(Files.move(f.path, path.resolve(f.name)))
      case d: ZDir  => ZDir(Files.move(d.path, path.resolve(d.name)))
    }
  }

  def delete: ZIO[Blocking, IOException, Unit] = effectBlocking {
    def loop(target: ZPath): Unit = {
      target match {
        case targetDir: ZDir =>
          listDir(targetDir.path).map(toZPath).foreach(loop)
          Files.deleteIfExists(targetDir.path)
        case targetFile: ZFile =>
          Files.deleteIfExists(targetFile.path)
      }
    }
    if (Files.exists(path))
      loop(this)
  }.refineToOrDie[IOException]

  def copy(other: ZDir): RIO[Blocking, Unit] = effectBlocking {
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

  // list

  def list: ZIO[Blocking, IOException, List[ZPath]] = effectBlocking {
    listDir(path).map(toZPath)
  }.refineToOrDie[IOException]

  def listFiles: ZIO[Blocking, IOException, List[ZFile]] =
    list.map(ZPath.pickFiles(_))

  def listDirs: ZIO[Blocking, IOException, List[ZDir]] =
    list.map(ZPath.pickDirs(_))

  // walk

  def walk: ZIO[Blocking, IOException, List[ZPath]] = effectBlocking {
    walkDir(path).map(toZPath)
  }.refineToOrDie[IOException]

  def walkFiles: ZIO[Blocking, IOException, List[ZFile]] =
    walk.map(ZPath.pickFiles(_))

  def walkDirs: ZIO[Blocking, IOException, List[ZDir]] =
    walk.map(ZPath.pickDirs(_))

  def streamWalk: ZStream[Blocking, Throwable, ZPath] =
    ZStream.unfoldChunkM(new WalkIter(path))(_.next)

  def streamWalkFiles: ZStream[Blocking, Throwable, ZFile] =
    streamWalk.filter(_.isFile).map(_.asInstanceOf[ZFile])

  def streamWalkDirs: ZStream[Blocking, Throwable, ZDir] =
    streamWalk.filter(_.isDir).map(_.asInstanceOf[ZDir])

  private class WalkIter(path: Path) {

    var iterator: Option[Iterator[Path]] = None

    def iter = iterator match {
      case Some(iter) => ZIO.succeed(iter)
      case None => for {
          iter  <- effectBlocking(Files.walk(path).iterator.asScala)
          _     <- ZIO.succeed { iterator = Some(iter) }
        } yield iter
    }

    def take(iter: Iterator[Path]) = effectBlocking {
      iter.take(100).toList.map(toZPath)
    }
      
    val toChunk: List[ZPath] => Option[(Chunk[ZPath], WalkIter)] = {
      case Nil => None
      case batch => Some((Chunk.fromIterable(batch), this))
    }

    def next = iter.flatMap(take).map(toChunk)
  }
}



















