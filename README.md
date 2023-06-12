# zio-path

zio-path is a simple library for working with files and folders in ZIO. It treats files and folders seperately, ZFile and ZDir.

Please create an issue if you find any bugs or, for example, if you want me to make it accessable as a package on Maven.

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
  _       <- imgDir.file("pic.jpg").fillFrom(new URL("http://images.com/pic.jpg"))
  _       <- oldFolder.delete
  files   <- filesDir.listFiles
} yield files
```

#### ZPath

##### Static:
```scala
def fromPath(path: Path) = Task[ZPath]
```

Methods common to `ZFile` and `ZDir`
##### Methods:
```scala
val path: Path
def isFile: Boolean
def isDir: Boolean
def name: String
def startsWithDot: Boolean
def parent: ZDir
def delete: Task[Unit]
def copy(dest: ZDir): Task[Unit]
def size: Task[Long]
def exists: Task[Boolean]
def info: Task[ZPathInfo]
``` 

#### ZFile

##### Static:
```scala
def fromPath(path: Path) = ZFile
def rel(relPath: String): ZFile
def get(path: String): ZFile 
def get(dir: ZDir, path: String): ZFile
def deleteFiles(files: Seq[ZFile]): Task[Unit]
```

##### Methods:
```scala
def ext: String
def extUpper: String
def extLower: String
def readBytes: Task[Array[Byte]]
def readString: Task[String]
def readLines: Task[List[String]]
def write(bytes: Array[Byte]): Task[Unit]
def write(str: String): Task[Unit]
def write(lines: Seq[String]): Task[Unit]
def append(bytes: Array[Byte]): Task[Unit]
def append(str: String): Task[Unit]
def append(lines: Seq[String]): Task[Unit]
def size: Task[Long]
def create: Task[ZFile]
def delete: Task[Unit]
def copy(target: ZFile): Task[Unit]
def copy(dest: ZDir): Task[Unit]
def rename(target: ZFile): Task[ZFile]
def rename(fileName: String): Task[ZFile]
def moveTo(dest: ZDir): Task[ZFile]
def fillFrom(url: URL): Task[Long]
def asSink: ZSink[Any, Throwable, Byte, Byte, Long]
def asStringSink: ZSink[Any, Throwable, String, Byte, Long]
def streamBytes: ZStream[Any, Throwable, Byte]
def streamLines: ZStream[Any, Throwable, String]
```

#### ZDir

##### Static:
```scala
def fromPath(path: Path) = ZDir
def rel(relPath: String): ZDir
def get(path: String): ZDir
def get(dir: ZDir, path: String): ZDir
def mkdirs(dirs: Seq[ZDir]): Task[Seq[ZDir]]
```

##### Methods:
```scala
def add(other: ZPath): ZPath
def add(other: ZFile): ZFile
def add(other: ZDir): ZDir
def file(fileName: String): ZFile
def dir(dirName: String): ZDir
def size: Task[Long]
def create: Task[Unit]
def delete: Task[Unit]
def copy(other: ZDir): Task[Unit]
def mkdir(dirName: String): Task[ZDir]
def mkdirs(dirNames: Seq[String]): Task[Seq[ZDir]]
def rename(dirName: String): Task[ZDir]
def moveTo(dest: ZDir): Task[ZDir]
def moveHere(paths: Seq[ZPath]): Task[Seq[ZPath]]
def list: Task[List[ZPath]]
def listFiles: Task[List[ZFile]]
def listDirs: Task[List[ZDir]]
def walk: Task[List[ZPath]]
def walkFiles: Task[List[ZFile]]
def walkDirs: Task[List[ZDir]]
```

















