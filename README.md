[![Twitter URL](https://img.shields.io/twitter/url/https/twitter.com/bukotsunikki.svg?style=social&label=Follow%20%40kuzminki_lib)](https://twitter.com/kuzminki_lib)

# zio-path

zio-path is a simple library for working with files and folders in ZIO. It is a wrapper for java.nio.files.Path. Files and folders are handled separately with ZFile and ZDir which provide convenient methods for working with files and folders in ZIO. This library is available for ZIO 1 and ZIO 2.

The latest release candidate, x.0.2-RC3 adds methods to compress and uncompress gzip, zip and tar. Also methods for file upload and download. The documentation below is for 2.0.2-RC3.

#### Sbt
```sbt
// available for Scala 2.12, 2.13 and 3

// for ZIO 1
libraryDependencies += "io.github.karimagnusson" %% "zio-path" % "1.0.1"

// for ZIO 2
libraryDependencies += "io.github.karimagnusson" %% "zio-path" % "2.0.1"
```

#### Create instance
```scala
import io.github.karimagnusson.zio.path._

// ZFile and ZDir are wrappers for Path and created in the same way.
val textFile = ZFile(Paths.get("/path/to/files/file.txt"))
val filesDir = ZDir.rel("files") // relative path
val imgFile = ZFile.get(filesDir, "image.jpg")
```

#### Example
```scala
import io.github.karimagnusson.zio.path._

val filesDir = ZDir.rel("files")
val textFile = filesDir.file("text.txt")
val oldFolder = filesDir.dir("old-folder")

val job = for {
  lines   <- textFile.readLines
  _       <- filesDir.file("text-copy.txt").write(lines)
  imgDir  <- filesDir.mkdir("images")
  gzFile  <- imgDir.file("doc.txt.gz").download("http://mysite.com/doc.txt.gz")
  _       <- gzFile.ungzip
  _       <- oldFolder.delete
  files   <- filesDir.listFiles
} yield files
```

#### ZPath

##### Static:
```scala
def fromPath(path: Path) = Task[ZPath] // ZFile or ZDir
def rel(parts: String*) = Task[ZPath] // ZFile or ZDir
def get(first: String, rest: String*) = Task[ZPath] // ZFile or ZDir
def pickFiles(paths: List[ZPath]): List[ZFile]
def pickDirs(paths: List[ZPath]): List[ZDir]
```

Methods common to `ZFile` and `ZDir`
##### Methods:
```scala
val path: Path
def name: String // Name of the file or folder
def isFile: Boolean
def isDir: Boolean
def startsWithDot: Boolean
def parent: ZDir
def delete: IO[IOException, Unit] // A folder will be deleted recursively
def copyTo(dest: ZDir): Task[Unit] // A folder will be copied with all its contents
def size: IO[IOException, Long] // If folder, then the size of all the containing files and folders
def isEmpty: IO[IOException, Boolean]
def nonEmpty: IO[IOException, Boolean]
def exists: UIO[Boolean]
def info: IO[IOException, ZPathInfo]
``` 

#### ZFile

##### Static:
```scala
def fromPath(path: Path) = ZFile
def rel(parts: String*): ZFile // Relative to working directory. Returns full path. 
def get(first: String, rest: String*): ZFile 
def deleteFiles(files: Seq[ZFile]): IO[IOException, Unit]
```

##### Methods:
```scala
def isFile: Boolean
def isDir: Boolean
def ext: Option[String]
def extUpper: String
def extLower: String
def relTo(dir: ZDir): ZFile // The rest of the path relative to dir
def assert: IO[IOException, ZFile] // Assert that the file exists and that it is a file
def create: Task[ZFile]
def size: IO[IOException, Long]
def isEmpty: IO[IOException, Boolean]
def nonEmpty: IO[IOException, Boolean]
def delete: IO[IOException, Unit]
def readBytes: IO[IOException, Array[Byte]]
def readString: IO[IOException, String]
def readLines: IO[IOException, List[String]]
def write(bytes: Array[Byte]): Task[Unit]
def write(str: String): Task[Unit]
def write(lines: Seq[String]): Task[Unit]
def append(bytes: Array[Byte]): Task[Unit]
def append(str: String): Task[Unit]
def append(lines: Seq[String]): Task[Unit]
def copyTo(target: ZFile): Task[Unit]
def copyTo(dest: ZDir): Task[Unit]
def rename(target: ZFile): Task[ZFile]
def rename(fileName: String): Task[ZFile]
def moveTo(dest: ZDir): Task[ZFile]
def mimeType: Task[String]
def gzip: Task[ZFile]
def gzip(out: ZFile): Task[ZFile]
def ungzip: Task[ZFile]
def ungzip(out: ZFile): Task[ZFile]
def unzip: Task[ZDir]
def unzip(dest: ZDir): Task[ZDir]
def untar: Task[ZDir]
def untar(dest: ZDir): Task[ZDir]
def untarGz: Task[ZDir]
def untarGz(dest: ZDir): Task[ZDir]
def download(url: String): Task[ZFile] // Download file contents from URL to this file
def download(url: String, headers: Map[String, String]): Task[ZFile]
def upload(url: String): Task[String]
def upload(url: String, headers: Map[String, String]): Task[String]
def asSink: ZSink[Any, Throwable, Byte, Byte, Long]
def asStringSink: ZSink[Any, Throwable, String, Byte, Long]
def streamBytes: ZStream[Any, Throwable, Byte]
def streamLines: ZStream[Any, Throwable, String]
```

#### ZDir

##### Static:
```scala
def fromPath(path: Path): ZDir
def rel(parts: String*): ZDir // Relative to working directory. Returns full path.
def get(first: String, rest: String*): ZDir
def mkdirs(dirs: Seq[ZDir]): IO[IOException, Seq[ZDir]]: Seq[ZDir]
```

##### Methods:
```scala
def isFile: Boolean
def isDir: Boolean
def relTo(other: ZDir): ZDir // The rest of the path relative to other
def add(other: ZPath): ZPath
def add(other: ZFile): ZFile
def add(other: ZDir): ZDir
def file(fileName: String): ZFile
def dir(dirName: String): ZDir
def assert: IO[IOException, ZDir] // Assert that the folder exists and that it is a folder
def size: IO[IOException, Long] // The combined size of all the containing files and folders
def isEmpty: IO[IOException, Boolean]
def nonEmpty: IO[IOException, Boolean]
def create: Task[ZDir]
def mkdir(dirName: String): Task[ZDir]
def mkdirs(dirNames: Seq[String]): IO[IOException, Seq[ZDir]]
def rename(dest: ZDir): Task[ZDir]
def rename(dirName: String): Task[ZDir]
def moveTo(dest: ZDir): Task[ZDir]
def moveHere(paths: Seq[ZPath]): Task[Seq[ZPath]]
def delete: IO[IOException, Unit] // Delete the folder and all its contents
def copyTo(other: ZDir): Task[Unit] // Copy the folder and all its contents
def zip: Task[ZFile]
def zip(out: ZFile): Task[ZFile]
def tar: Task[ZFile]
def tar(dest: ZDir): Task[ZFile]
def tarGz: Task[ZFile]
def tarGz(dest: ZDir): Task[ZFile]
def list: IO[IOException, List[ZPath]] // List all the files and folders
def listFiles: IO[IOException, List[ZFile]]
def listDirs: IO[IOException, List[ZDir]]
def walk: IO[IOException, List[ZPath]]
def walkFiles: IO[IOException, List[ZFile]]
def walkDirs: IO[IOException, List[ZDir]]
def streamWalk: ZStream[Any, Throwable, ZPath]
def streamWalkFiles: ZStream[Any, Throwable, ZFile]
def streamWalkDirs: ZStream[Any, Throwable, ZDir]
```

















