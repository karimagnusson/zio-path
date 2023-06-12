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

import scala.collection.JavaConverters._

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
  def fromPath(path: Path): RIO[Blocking, ZPath] = effectBlocking {
    if (Files.isDirectory(path)) ZDir(path) else ZFile(path)
  }
}


sealed trait ZPath {
  
  val path: Path
  def delete: RIO[Blocking, Unit]
  def copy(dest: ZDir): RIO[Blocking, Unit]
  def size: RIO[Blocking, Long]

  def name = path.getFileName.toString
  def startsWithDot = name.head == '.'
  def parent = ZDir(path.getParent)

  def isFile: Boolean = this match {
    case _: ZFile => true
    case _: ZDir  => false
  }

  def isDir: Boolean = this match {
    case _: ZDir  => true
    case _: ZFile => false
  }

  def exists: RIO[Blocking, Boolean] = effectBlocking {
    Files.exists(path)
  }

  def info: RIO[Blocking, ZPathInfo] = effectBlocking {
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
  
  def deleteFiles(files: Seq[ZFile]): RIO[Blocking, Unit] = effectBlocking {
    files.foreach(f => Files.deleteIfExists(f.path))
  }
}


case class ZFile(path: Path) extends ZPath {

  def ext = name.split('.').lastOption
  def extUpper = ext.map(_.toUpperCase).getOrElse("")
  def extLower = ext.map(_.toLowerCase).getOrElse("")

  def create: RIO[Blocking, ZFile] = for {
    _ <- effectBlocking(Files.createFile(path))
  } yield this

  def size: RIO[Blocking, Long] = effectBlocking {
    Files.size(path)
  }

  def delete: RIO[Blocking, Unit] = effectBlocking {
    Files.deleteIfExists(path)
  }

  // read

  def readBytes: RIO[Blocking, Array[Byte]] = effectBlocking {
    Files.readAllBytes(path)
  }

  def readString: RIO[Blocking, String] = for {
    bytes <- readBytes
  } yield bytes.map(_.toChar).mkString

  def readLines: RIO[Blocking, List[String]] = for {
    content <- readString
  } yield content.split("\n").toList

  // write

  def write(bytes: Array[Byte]): RIO[Blocking, Unit] = effectBlocking {
    Files.write(path, bytes)
  }

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
  def rel(relPath: String) = ZDir(Paths.get(ZPath.root, relPath))
  def get(path: String) = ZDir(Paths.get(path))
  def get(dir: ZDir, path: String) = ZDir(Paths.get(dir.toString, path))

  def mkdirs(dirs: Seq[ZDir]): RIO[Blocking, Seq[ZDir]] = effectBlocking {
    dirs.map(_.path).filterNot(Files.exists(_)).foreach(Files.createDirectory(_))
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

  private val pickFiles: List[ZPath] => List[ZFile] = { paths =>
    paths.filter(_.isFile).map(_.asInstanceOf[ZFile])
  } 

  private val pickDirs: List[ZPath] => List[ZDir] = { paths =>
    paths.filter(_.isDir).map(_.asInstanceOf[ZDir])
  } 

  def size: RIO[Blocking, Long] = effectBlocking {
    walkDir(path).foldLeft(0L) { (acc, p) => acc + Files.size(p) }
  }

  def add(other: ZPath): ZPath = other match {
    case ZFile(otherPath) => ZFile(path.resolve(otherPath))
    case ZDir(otherPath) => ZDir(path.resolve(otherPath))
  }

  def add(other: ZFile) = ZFile(path.resolve(other.path))

  def add(other: ZDir) = ZDir(path.resolve(other.path))

  def file(fileName: String) = add(ZFile.get(fileName))

  def dir(dirName: String) = add(ZDir.get(dirName))

  def mkdir(dirName: String): RIO[Blocking, ZDir] = dir(dirName).create

  def mkdirs(dirNames: Seq[String]): RIO[Blocking, Seq[ZDir]] = ZDir.mkdirs(dirNames.map(dir))

  def create: RIO[Blocking, ZDir] = for {
    _ <- effectBlocking(Files.createDirectories(path))
  } yield this

  // list

  def list: RIO[Blocking, List[ZPath]] = effectBlocking {
    listDir(path).map(toZPath)
  }

  def listFiles: RIO[Blocking, List[ZFile]] = list.map(pickFiles)

  def listDirs: RIO[Blocking, List[ZDir]] = list.map(pickDirs)

  def walk: RIO[Blocking, List[ZPath]] = effectBlocking {
    walkDir(path).map(toZPath)
  }

  // walk

  def walkFiles: RIO[Blocking, List[ZFile]] = walk.map(pickFiles)

  def walkDirs: RIO[Blocking, List[ZDir]] = walk.map(pickDirs)

  def rename(dest: ZDir): RIO[Blocking, ZDir] = for {
    _ <- effectBlocking(Files.move(path, dest.path))
  } yield dest

  // rename

  def rename(dirName: String): RIO[Blocking, ZDir] = rename(parent.dir(dirName))

  def moveTo(dest: ZDir): RIO[Blocking, ZDir] = rename(dest.dir(name))

  def moveHere(paths: Seq[ZPath]): RIO[Blocking, Seq[ZPath]] = for {
    pairs <- ZIO.effect {
      paths.map {
        case source: ZFile => (source, file(source.name))
        case source: ZDir => (source, dir(source.name)) 
      }
    }
    _     <- effectBlocking {
      pairs.foreach {
        case (source, dest) =>
          Files.move(source.path, dest.path)
      }
    }
  } yield pairs.map(_._2)

  def delete: RIO[Blocking, Unit] = effectBlocking {
    def loop(target: ZPath) {
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
}



















