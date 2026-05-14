package p2p.controller;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import p2p.service.FileSharer;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.Charset;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.apache.commons.io.IOUtils;

public class FileController {
    private final FileSharer fileSharer;
    private final HttpServer server;
    private final String uploadDir;
    private final ExecutorService executorService;

    // What's the flow (client) --->Server(storesInTemp) --> and then send it to the receiver;
    public FileController(int port) throws IOException {
        this.fileSharer = new FileSharer();
        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        this.uploadDir = System.getProperty("java.io.tempdir") + File.separator + "peerlinnk-upload";
        this.executorService = Executors.newFixedThreadPool(10);


        File uploadDirFile = new File(uploadDir); // Make sure that if the file is not exist it will create that
        if(!uploadDirFile.exists())
        {
            uploadDirFile.mkdirs();
        }


        server.createContext("/upload" , new UploadHandler());
        server.createContext("/download" , new DownloadHandler());
        server.createContext("/", new CORSHandler()); // if the service is distributed we have to store the ip and all;
        server.setExecutor(executorService);  // Server will use the about threadPool;

    }

    public  void start() // this will start the server
    {
        server.start();
        System.out.println("Api server start on port"+server.getAddress().getPort());
    }

    public void stop()
    {
        server.stop(0);
        executorService.shutdown();
        System.out.println("Api Server Stopped");
    }

    private class CORSHandler implements HttpHandler
    {

        @Override
        public void handle(HttpExchange exchange) throws IOException
        {
            Headers headers = exchange.getResponseHeaders();
            headers.add("Access-Control-Allow-Origin", "*");
            headers.add("Access-Control-Allow-Methods", "GET , POST, OPTIONS");
            headers.add("Access-Control-Add-Headers", "Content-Type , Authorization");

            if(exchange.getRequestMethod().equals("OPTIONS"))
            {
                exchange.sendResponseHeaders(204,-1);
                return;
            }

            String response = "NOT FOUND";
            exchange.sendResponseHeaders(404, response.getBytes().length);

            try(OutputStream oos = exchange.getResponseBody())
            {
                oos.write(response.getBytes());
            }

        }
    }

    public class UploadHandler implements HttpHandler
    {
        @Override
        public void handle(HttpExchange exchange) throws IOException
        {
            Headers headers = exchange.getResponseHeaders();
            headers.add("Access-Control-Allow-Origin", "*");

            if(!exchange.getRequestMethod().equalsIgnoreCase("POST"))
            {
                String response = "Methood Not Allowed";
                exchange.sendResponseHeaders(405,response.getBytes().length);
                try(OutputStream os = exchange.getResponseBody())
                {
                    os.write(response.getBytes());
                }
                return;
            }

            Headers requestHeaders = exchange.getRequestHeaders();
            String contentType = requestHeaders.getFirst("Content-Type");

            if(contentType == null || !contentType.startsWith("multipart/form-data"))
            {
                String response = "Bad Request: Content-Type must be 'multipart/form-data'";
                exchange.sendResponseHeaders(400,response.getBytes().length);

                try(OutputStream os = exchange.getResponseBody()){
                    os.write(response.getBytes());
                }
                return;
            }
            try
            {
                String boundary = contentType.substring(contentType.indexOf("boundary=")+9);
                ByteArrayOutputStream baos = new ByteArrayOutputStream();

                IOUtils.copy(exchange.getRequestBody(), baos);

                byte[] requestData = baos.toByteArray();

                MUltiParser parser = new MUltiParser(requestData , boundary);
                MUltiParser.ParseResult result = parser.parse();

                if(result == null)
                {
                    String response = "Bad Request could not parse file content";
                    exchange.sendResponseHeaders(400,response.getBytes().length);
                    try(OutputStream os = exchange.getResponseBody())
                    {
                        os.write(response.getBytes());
                    }
                    return;
                }

                String fileName = result.fileName;
                if(fileName == null || fileName.trim().isEmpty())
                {
                    fileName="unnamed-file";
                }
                String uniqueFileName = UUID.randomUUID().toString()+ "-"+new File(fileName).getName();

                String filePath = uploadDir + File.separator + uniqueFileName;

                try(FileOutputStream fos = new FileOutputStream(filePath))
                {
                    fos.write(result.fileContent);
                }
                int port = fileSharer.offerFile(filePath);
                new Thread(() -> fileSharer.startFileServer(port)).start();

                // String jsonResponse = "{\"port\": }"+port + "}"; // "port"
                String jsonResponse = "{\"port\": " + port + "}";

                headers.add("Content-Type" , "application/json");

                exchange.sendResponseHeaders(200,jsonResponse.getBytes().length);
                try(OutputStream os = exchange.getResponseBody())
                {
                    os.write(jsonResponse.getBytes());
                }


            }
            catch (Exception e)
            {
                System.err.println("Error Processing file upload" + e.getMessage());
                String response = "Server Error"  + e.getMessage();
                exchange.sendResponseHeaders(500,response.getBytes().length);
                try(OutputStream os = exchange.getResponseBody())
                {
                    os.write(response.getBytes());
                }
            }
        }

    }

    private static class MUltiParser {
        private final byte[] data;
        private final String boundary;


        public MUltiParser(byte[] data, String boundary) {
            this.data = data;
            this.boundary = boundary;
        }

        public ParseResult parse() {
            try {
                String dataAsString = new String(data); // Todo : For Videos extend this and it will be Object
                String filenameMarker = "filename=\"";
                int fileNameStart = dataAsString.indexOf(filenameMarker);
                if (fileNameStart == -1) {
                    return null;
                }

                int fileNameEnd = dataAsString.indexOf("\"", fileNameStart);

                String fileName = dataAsString.substring(fileNameStart, fileNameEnd);

                String contentTypeMarker = "Content-Type";
                int contentTypeStart = dataAsString.indexOf(contentTypeMarker, fileNameEnd);
                String contentType = "application/octet-stream";
                if (contentTypeStart != -1) {
                    contentTypeStart = contentTypeStart + contentTypeMarker.length();
                    int contentTypeEnd = dataAsString.indexOf("\r\n", contentTypeStart);
                    contentType = dataAsString.substring(contentTypeStart, contentTypeEnd);

                }
                String headerEndMarker = "\r\n\r\n";

                int headerEnd = dataAsString.indexOf(headerEndMarker);

                if (headerEnd == -1) {
                    return null;
                }

                int contentStart = headerEnd + headerEndMarker.length();

                byte[] boundaryBytes = ("\r\n--" + boundary + "--").getBytes();
                int contentEnd = fileSequence(data, boundaryBytes, contentStart);

                if (contentEnd == -1) {
                    boundaryBytes = ("\r\n--" + boundary).getBytes();
                    contentEnd = fileSequence(data, boundaryBytes, contentStart);

                }
                if (contentEnd == -1 || contentEnd <= contentStart) {
                    return null;
                }

                byte[] fileContent = new byte[contentEnd - contentStart];

                System.arraycopy(data, contentStart, fileContent, 0, fileContent.length);

                return new ParseResult(fileName, fileContent, contentType);

            } catch (Exception e) {
                System.out.println("Error parsing multipart data" + e.getMessage());
                return null;
            }
        }


        public static class ParseResult {
            public final String fileName;
            public final byte[] fileContent;
            public final String contentType;

            public ParseResult(String fileName, byte[] fileContent, String contentType) {
                this.fileName = fileName;
                this.fileContent = fileContent;
                this.contentType = contentType;
            }
        }

        private static int fileSequence(byte[] data, byte[] sequence, int startPosition) {
            outer:
            for (int i = startPosition; i <= data.length - sequence.length; i++) {
                for (int j = 0; j < sequence.length; j++) {
                    if (data[i + j] != sequence[j]) {
                        continue outer;
                    }
                }
                return i;
            }
            return -1;
        }
    }


    public class DownloadHandler  implements HttpHandler
    {


        @Override
        public void handle(HttpExchange exchange) throws IOException
        {
            Headers headers = exchange.getResponseHeaders();
            headers.add("Access-Control-Allow-Origin", "*");

            if(!exchange.getRequestMethod().equalsIgnoreCase("GET"))
            {
                String response = "Method Not Allowed";
                exchange.sendResponseHeaders(405, response.getBytes().length);
                try (OutputStream os = exchange.getResponseBody())
                {
                    os.write(response.getBytes());
                }
                return;
            }

            String path = exchange.getRequestURI().getPath();
            String portStr = path.substring(path.lastIndexOf('/') + 1);

            try{
                int port = Integer.parseInt(portStr);

                try(Socket socket = new Socket("localhost", port))
                {
                    InputStream socketInput = socket.getInputStream();
                    File tempFile = File.createTempFile("download", ".tmp");

                    String fileName = "downloaded-file";

                    try(FileOutputStream fos = new FileOutputStream(tempFile))
                    {
                        byte[] buffer = new byte[4096];
                        int bytesRead;
                        ByteArrayOutputStream HeaderBos = new ByteArrayOutputStream();
                        int b;
                        while ((b= socketInput.read()) !=-1)
                        {
                            if(b == '\n') break;
                            HeaderBos.write(b);
                        }
                        String header = HeaderBos.toString().trim();

                        if(header.startsWith("FileName: "))
                        {
                            fileName = header.substring("FileName: ".length());
                        }
                        while ((bytesRead = socketInput.read(buffer)) != -1)
                        {
                            fos.write(buffer, 0, bytesRead);
                        }
                    }

                    headers.add("Content-Disposition" , "attachment; fileName=\"" +  fileName + "\"");
                    headers.add("Content-Type", "application/octet-stream");
                    exchange.sendResponseHeaders(200 , tempFile.length());

                    try(OutputStream os = exchange.getResponseBody())
                    {
                        FileInputStream fis = new FileInputStream(tempFile);
                        byte[] buf = new byte[4096];
                        int byteRead;
                        while ((byteRead = fis.read(buf)) != -1)
                        {
                            os.write(buf, 0, byteRead);
                        }
                    }

                    tempFile.delete();
                }
            }
            catch (Exception e)
            {
                System.out.println("Error Downloading File" + e.getMessage());
                String response = "Error Downloading File";

                headers.add("Content-Type", "text/plain");

                exchange.sendResponseHeaders(400, response.getBytes().length);

                try (OutputStream os = exchange.getResponseBody())
                {
                    os.write(response.getBytes());
                }
            }



        }
    }


}
