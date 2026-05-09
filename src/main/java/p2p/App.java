package p2p;

import p2p.controller.FileController;

public class App {
    public static void main(String[] args)
    {
        try
        {
            FileController fileController = new FileController(8080);
            fileController.start();
            System.out.println("PeerLink Server Started on port 8000");
            System.out.println("UI available at http://localhost:3000");

            Runtime.getRuntime().addShutdownHook(
                    new Thread(
                            () ->{
                                System.out.println("Shutting down PeerLink Server");
                                fileController.stop();
                            }
                    )
            );
            System.out.println("Press Enter to Shut Down The Server");

            System.in.read(); //  TODO: Shut Down the server if someone presses the enter;
        }
        catch (Exception e){
            System.err.println("Failed to start the start the server at port 8080");
            e.printStackTrace();
        }
    }
}
