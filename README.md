# zio-path

zio-path is a simple library to work with files and folders in ZIO. It treats files and folders seperately, ZFile and ZDir.

Please create an issue if you find any bugs or, for example, if you want me to make it accessable as a package on Maven.

#### Example

```scala
import io.github.karimagnusson.zio.path._

val filesDir = ZDir.rel("files")
val textFile = filesDir.file("text.txt")
val oldFolder = filesDir.dir("old-folder")

val job = for {
  lines   <- textFile.readLines
  _       <- filesDir.file("text-copy.txt").writeLines(lines)
  imgDir  <- filesDir.mkdir("images")
  _       <- imgDir.file("pic.jpg").fillFrom(new URL("http://images.com/pic.jpg"))
  _       <- oldFolder.deleteRecursively
  files   <- filesDir.listFiles
} yield files
```

#### ZPath
Methods common to `ZFile` and `ZDir`

```scala
val path: Path
def name: String
def startsWithDot: Boolean
def parent: ZDir
def moveTo(dest: ZPath): Task[Unit]
def exists: Task[Boolean]
def size: Task[Long]
def info: Task[ZPathInfo]
``` 

#### ZFile

##### Static:
```scala
def rel(relPath: String): ZFile
def get(path: String): ZFile 
def get(dir: ZDir, path: String): ZFile
def deleteFiles(files: Seq[ZFile]): Task[Unit]
```

##### Methods:
```scala
def ext: String
def readBytes: Task[Array[Byte]]
def readString: Task[String]
def readLines: Task[List[String]]
def writeBytes(bytes: Array[Byte]): Task[Unit]
def writeString(str: String): Task[Unit]
def writeLines(lines: Seq[String]): Task[Unit]
def appendBytes(bytes: Array[Byte]): Task[Unit]
def appendString(str: String): Task[Unit]
def appendLines(lines: Seq[String]): Task[Unit]
def copy(target: ZFile): Task[Unit]
def delete: Task[Unit]: Task[Unit]
def fillFrom(url: URL): Task[Long]
def asSink: ZSink[Any, Throwable, Byte, Byte, Long]
def asStringSink: ZSink[Any, Throwable, String, Byte, Long]
def streamBytes: ZStream[Any, Throwable, Byte]
def streamLines: ZStream[Any, Throwable, String]
```

#### ZDir

##### Static:
```scala
def rel(relPath: String): ZDir
def get(path: String): ZDir
def get(dir: ZDir, path: String): ZDir
```

##### Methods:

```scala
def add(other: ZFile): ZFile
def add(other: ZDir): ZDir
def ++(other: ZFile): ZFile
def ++(other: ZDir): ZDir
def file(fileName: String): ZFile
def dir(dirName: String): ZDir
def mkdir(dirName: String): Task[ZDir]
def create: Task[Unit]
def createAll: Task[Unit]
def list: Task[List[ZPath]]
def listFiles: Task[List[ZFile]]
def listDirs: Task[List[ZDir]]
def deleteDir: Task[Unit]
def deleteRecursively: Task[Unit]
```

















