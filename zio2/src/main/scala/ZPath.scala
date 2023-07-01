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

//import scala.collection.JavaConverters._
import scala.jdk.CollectionConverters._

import zio._
import zio.stream.{ZStream, ZPipeline, ZSink}


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
  
  def fromPath(path: Path): Task[ZPath] = ZIO.attemptBlocking {
    if (Files.isDirectory(path)) ZDir(path) else ZFile(path)
  }

  def pickFiles(paths: List[ZPath]): List[ZFile] = {
    paths.filter(_.isFile).map(_.asInstanceOf[ZFile])
  } 

  def pickDirs(paths: List[ZPath]): List[ZDir] = {
    paths.filter(_.isDir).map(_.asInstanceOf[ZDir])
  }
}


sealed trait ZPath {
  
  val path: Path
  def delete: Task[Unit]
  def copy(dest: ZDir): Task[Unit]
  def size: Task[Long]
  def isEmpty: Task[Boolean]
  def nonEmpty: Task[Boolean]
  def isFile: Boolean
  def isDir: Boolean

  def name = path.getFileName.toString
  def show = path.toString
  def startsWithDot = name.head == '.'
  def parent = ZDir(path.getParent)

  def exists: Task[Boolean] = ZIO.attemptBlocking {
    Files.exists(path)
  }

  def info: Task[ZPathInfo] = ZIO.attemptBlocking {
    ZPathInfo(
      path,
      Files.isDirectory(path),
      Files.isHidden(path),
      Files.isReadable(path),
      Files.isWritable(path),
      Files.isSymbolicLink(path),
      Files.getLastModifiedTime(path)
    )
  }

  override def toString = path.toString
}


object ZFile {

  def fromPath(path: Path) = ZFile(path)
  def rel(relPath: String) = ZFile(Paths.get(ZPath.root, relPath))
  def get(path: String) = ZFile(Paths.get(path))
  def get(dir: ZDir, path: String) = ZFile(Paths.get(dir.toString, path))
  
  def deleteFiles(files: Seq[ZFile]): Task[Unit] = ZIO.attemptBlocking {
    files.foreach(f => Files.deleteIfExists(f.path))
  }
}


case class ZFile(path: Path) extends ZPath {

  def ext = name.split('.').lastOption
  def extUpper = ext.map(_.toUpperCase).getOrElse("")
  def extLower = ext.map(_.toLowerCase).getOrElse("")

  def isFile = true
  def isDir = false

  def create: Task[ZFile] = for {
    _ <- ZIO.attemptBlocking(Files.createFile(path))
  } yield this

  def size: Task[Long] = ZIO.attemptBlocking {
    Files.size(path)
  }

  def isEmpty: Task[Boolean] = size.map(_ == 0)
  def nonEmpty: Task[Boolean] = size.map(_ > 0)

  def delete: Task[Unit] = ZIO.attemptBlocking {
    Files.deleteIfExists(path)
  }

  // read

  def readBytes: Task[Array[Byte]] = ZIO.attemptBlocking {
    Files.readAllBytes(path)
  }

  def readString: Task[String] = for {
    bytes <- readBytes
  } yield bytes.map(_.toChar).mkString

  def readLines: Task[List[String]] = for {
    content <- readString
  } yield content.split("\n").toList

  // write

  def write(bytes: Array[Byte]): Task[Unit] = ZIO.attemptBlocking {
    Files.write(path, bytes)
  }

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

  def copy(target: ZFile): Task[Unit] = ZIO.attemptBlocking {
    Files.copy(path, target.path)
  }

  def copy(dest: ZDir): Task[Unit] = copy(dest.file(name))

  // rename

  def rename(target: ZFile): Task[ZFile] = for {
    _ <- ZIO.attemptBlocking(Files.move(path, target.path))
  } yield target

  def rename(fileName: String): Task[ZFile] = rename(parent.file(fileName))

  def moveTo(dest: ZDir): Task[ZFile] = rename(dest.file(name))

  def relTo(dir: ZDir) = ZFile(dir.path.relativize(path))

  // stream

  def fillFrom(url: URL): Task[Long] = 
    ZStream.fromInputStreamZIO(
      ZIO.attemptBlocking(url.openStream).refineToOrDie[IOException]
    ).run(asSink)

  def asSink: ZSink[Any, Throwable, Byte, Byte, Long] =
    ZSink.fromPath(path)

  def asStringSink: ZSink[Any, Throwable, String, Byte, Long] =
    asSink.contramapChunks[String](_.flatMap(_.getBytes))

  def streamBytes: ZStream[Any, Throwable, Byte] = 
    ZStream.fromPath(path)

  def streamLines: ZStream[Any, Throwable, String] =
    streamBytes
      .via(ZPipeline.utf8Decode)
      .via(ZPipeline.splitLines)
}


object ZDir {
  def fromPath(path: Path) = ZDir(path)
  def rel(relPath: String) = ZDir(Paths.get(ZPath.root, relPath))
  def get(path: String) = ZDir(Paths.get(path))
  def get(dir: ZDir, path: String) = ZDir(Paths.get(dir.toString, path))

  def mkdirs(dirs: Seq[ZDir]): Task[Seq[ZDir]] = ZIO.attemptBlocking {
    dirs.map(_.path).foreach(Files.createDirectories(_))
    dirs
  } 
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

  def size: Task[Long] = ZIO.attemptBlocking {
    walkDir(path).foldLeft(0L) { (acc, p) => acc + Files.size(p) }
  }

  def isEmpty: Task[Boolean] = list.map(_.isEmpty)
  def nonEmpty: Task[Boolean] = list.map(_.nonEmpty)

  def add(other: ZPath): ZPath = other match {
    case p: ZFile => add(p)
    case p: ZDir  => add(p)
  }

  def add(other: ZFile) = ZFile(path.resolve(other.path))

  def add(other: ZDir) = ZDir(path.resolve(other.path))

  def file(fileName: String) = add(ZFile.get(fileName))

  def dir(dirName: String) = add(ZDir.get(dirName))

  def mkdir(dirName: String): Task[ZDir] = dir(dirName).create

  def mkdirs(dirNames: Seq[String]): Task[Seq[ZDir]] =
    ZDir.mkdirs(dirNames.map(dir))

  def create: Task[ZDir] = for {
    _ <- ZIO.attemptBlocking(Files.createDirectories(path))
  } yield this

  def rename(dirName: String): Task[ZDir] = rename(parent.dir(dirName))

  def moveTo(dest: ZDir): Task[ZDir] = rename(dest.dir(name))

  def moveHere(paths: Seq[ZPath]): Task[Seq[ZPath]] = ZIO.attemptBlocking {
    paths.map {
      case f: ZFile => ZFile(Files.move(f.path, path.resolve(f.name)))
      case d: ZDir  => ZDir(Files.move(d.path, path.resolve(d.name)))
    }
  }

  def rename(dest: ZDir): Task[ZDir] = for {
    _ <- ZIO.attemptBlocking(Files.move(path, dest.path))
  } yield dest

  def relTo(other: ZDir) = ZDir(other.path.relativize(path))

  def delete: Task[Unit] = ZIO.attemptBlocking {
    def loop(target: ZPath): Unit = {
      target match {
        case targetDir: ZDir =>
          listDir(targetDir.path).map(toZPath).foreach(loop)
          Files.deleteIfExists(targetDir.path)
        case targetFile: ZFile =>
          Files.deleteIfExists(targetFile.path)
      }
    }
    loop(this)
  }

  def copy(other: ZDir): Task[Unit] = ZIO.attemptBlocking {
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

  def list: Task[List[ZPath]] = ZIO.attemptBlocking {
    listDir(path).map(toZPath)
  }

  def listFiles: Task[List[ZFile]] = list.map(ZPath.pickFiles(_))

  def listDirs: Task[List[ZDir]] = list.map(ZPath.pickDirs(_))

  // walk

  def walk: Task[List[ZPath]] = ZIO.attemptBlocking {
    walkDir(path).map(toZPath)
  }

  def walkFiles: Task[List[ZFile]] = walk.map(ZPath.pickFiles(_))

  def walkDirs: Task[List[ZDir]] = walk.map(ZPath.pickDirs(_))

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

















