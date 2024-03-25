package io.github.karimagnusson.zio.path.utils;


import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Files;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URLConnection;


public class UrlRequest {

    public static void download(URL url, Path path, Header[] headers) throws IOException {

        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");

        for (Header header : headers) {
            conn.setRequestProperty(header.getKey(), header.getValue());
        }

        int code = conn.getResponseCode();
        if (code == HttpURLConnection.HTTP_OK) {
            try (InputStream in = conn.getInputStream();
                 FileOutputStream out = new FileOutputStream(path.toFile())) {
 
                int bytesRead = -1;
                byte[] buffer = new byte[1024];
                while ((bytesRead = in.read(buffer)) != -1) {
                    out.write(buffer, 0, bytesRead);
                }
            }
        } else {
            throw new IOException("Request failed with code " + code);
        }
    }

    public static String upload(URL url, Path path, Header[] headers) throws IOException {

        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setDoOutput(true);
        conn.setRequestMethod("POST");

        String mime = Files.probeContentType(path);
        if (mime != null) {
            conn.setRequestProperty("Content-Type", mime);
        }

        conn.setRequestProperty("Content-Length", String.valueOf(Files.size(path)));

        for (Header header : headers) {
            conn.setRequestProperty(header.getKey(), header.getValue());
        }

        try (BufferedOutputStream bos = new BufferedOutputStream(conn.getOutputStream());
             BufferedInputStream bis = new BufferedInputStream(new FileInputStream(path.toFile()))) {

            int i;
            while ((i = bis.read()) > 0) {
                bos.write(i);
            }
        }

        int code = conn.getResponseCode();
        if (code == HttpURLConnection.HTTP_OK) {

            StringBuffer rsp = new StringBuffer();

            try (BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                String line;
                while ((line = in.readLine()) != null) {
                    rsp.append(line);
                }
                in.close();
            }

            return rsp.toString();
        } else {
            throw new IOException("Request failed with code " + code);
        }
    }
}

