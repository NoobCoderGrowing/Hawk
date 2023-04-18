package hawk.index.demo;

import hawk.index.core.directory.Directory;
import hawk.index.core.directory.MMapDirectory;
import hawk.index.core.reader.DirectoryReader;

import java.io.IOException;
import java.nio.file.Paths;

public class ReadIndex {

    public static void main(String[] args) throws IOException {
        Directory directory = MMapDirectory.open(Paths.get("/opt/temp"));
        DirectoryReader directoryReader =DirectoryReader.open(directory);
    }
}
