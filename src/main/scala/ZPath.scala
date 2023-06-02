package io.github.karimagnusson.zio.path

import java.net.URL
import java.util.Comparator
import java.io.IOException
import java.nio.file.attribute.FileTime
import java.nio.file.{
  Files,
  Paths,
  Path,
  StandardOpenOption,
  StandardCopyOption,
  LinkOption
}

import scala.collection.JavaConverters._

import zio._
import zio.stream.{ZStream, ZPipeline, ZSink}


case class ZPathInfo(
  path: Path,
  size: Long,
  isDir: Boolean,
  isHidden: Boolean,
  isReadable: Boolean,
  isWritable: Boolean,
  isSymbolicLink: Boolean,
  lastModified: FileTime
)


object ZPath {
  lazy val root = Paths.get("").toAbsolutePath.toString
}


trait ZPath {
  
  val path: Path

  def name = path.getFileName.toString

  def startsWithDot = name.head == '.'

  def parent = ZDir(path.getParent)

  def moveTo(dest: ZPath): Task[Unit] = ZIO.attemptBlocking {
    Files.move(path, dest.path)
  }

  def exists: Task[Boolean] = ZIO.attemptBlocking {
    Files.exists(path)
  }

  def size: Task[Long] = ZIO.attemptBlocking {
    Files.size(path)
  }

  def info: Task[ZPathInfo] = ZIO.attemptBlocking {
    ZPathInfo(
      path,
      Files.size(path),
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

  def ext = name.split('.').lastOption.map(_.toLowerCase)

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

  def writeBytes(bytes: Array[Byte]): Task[Unit] = ZIO.attemptBlocking {
    Files.write(path, bytes)
  }

  def writeString(str: String) =
    writeBytes(str.getBytes)

  def writeLines(lines: Seq[String]) =
    writeBytes(lines.mkString("\n").getBytes)

  // append

  def appendBytes(bytes: Array[Byte]): Task[Unit] = ZIO.attemptBlocking {
    Files.write(path, bytes, StandardOpenOption.APPEND)
  }

  def appendString(str: String) =
    appendBytes(str.getBytes)

  def appendLines(lines: Seq[String]) =
    appendBytes(("\n" + lines.mkString("\n")).getBytes)

  // copy

  def copy(target: ZFile): Task[Unit] = ZIO.attemptBlocking {
    Files.copy(path, target.path)
  }

  // delete

  def delete: Task[Unit] = ZIO.attemptBlocking {
    Files.deleteIfExists(path)
  }

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
}


case class ZDir(path: Path) extends ZPath {

  private def listDir(p: Path): List[Path] = {
    Files
      .list(p)
      .iterator
      .asScala
      .toList
  }

  private def contents: List[Path] = listDir(path)

  private val toZPath: Path => ZPath = { p =>
    if (Files.isDirectory(p)) ZDir(p) else ZFile(p)
  }

  def add(other: ZFile) = ZFile(path.resolve(other.path))

  def add(other: ZDir) = ZDir(path.resolve(other.path))

  def ++(other: ZFile): ZFile = add(other)

  def ++(other: ZDir): ZDir = add(other)

  def file(fileName: String) = add(ZFile.get(fileName))

  def dir(dirName: String) = add(ZDir.get(dirName))

  def mkdir(dirName: String): Task[ZDir] = for {
    newDir  <- ZIO.attempt(dir(dirName))
    _       <- newDir.create
  } yield newDir

  def create: Task[Unit] = ZIO.attemptBlocking {
    Files.createDirectory(path)
  }

  def createAll: Task[Unit] = ZIO.attemptBlocking {
    Files.createDirectories(path)
  }

  def list: Task[List[ZPath]] = ZIO.attemptBlocking {
    contents.map(toZPath)
  }

  def listFiles: Task[List[ZFile]] = for {
    list <- ZIO.attemptBlocking {
      contents.filter(Files.isRegularFile(_))
    }
  } yield list.map(ZFile(_))

  def listDirs: Task[List[ZDir]] = for {
    list <- ZIO.attemptBlocking {
      contents.filter(Files.isDirectory(_))
    }
  } yield list.map(ZDir(_))

  def deleteDir: Task[Unit] = ZIO.attemptBlocking {
    Files.deleteIfExists(path)
  }

  def deleteRecursively: Task[Unit] = ZIO.attemptBlocking {
    def loop(p: Path) {
      if (Files.isDirectory(p)) {
        listDir(p).map(loop)
      }
      Files.deleteIfExists(p)
    }
    loop(path)
  }
}



















