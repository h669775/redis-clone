import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;

public class Server {

    public static void main(String[] args) throws Exception {
        ServerSocket socket = new ServerSocket(6379);
        if (socket.getLocalPort() == 6379) {
            System.out.println("Server listening on port 6379!");
        }

        Store store = new Store(); //Will store data in memory

        
        while (true) { //creates a loop so that different clients can connect after eachother without the server stopping. It blocks until a client connects.

        Socket client = socket.accept(); //accept pauses the program until a client connects. When one does it return a socket object representing that one secific connection, stored in client
        
        new Thread(() -> {
            try { //Try catch to catch IOexception
            InputStream rawInput = client.getInputStream(); //Gets the raw inputstream from that client. Reads byte from the client
        InputStreamReader textInput = new InputStreamReader(rawInput); //Wraps that raw byte stream, which converts bytes into characters (text encoding)
        BufferedReader reader = new BufferedReader(textInput); //Wraps that in a bufferedReader where the .readLine() method reads a full line of text at a time instead of one character at a time
       
        String text; //Holds whatever line gets read later.
        PrintWriter pw = new PrintWriter(client.getOutputStream(), true); //Is what the client sees as output from the serer (they will see OK!) AKA the response the client receives.



        //Loop for å håndtere en eller fleire requests fra klienten
        while((text = reader.readLine()) != null) {
        System.out.println(text);

        //Extract the count from the REDIS formatted input from the client
        int count = Integer.parseInt(text.substring(1)); //count is the number of elements in the command so the loop knows how many times to iterate
        
        //Splits into array just like .split(text) did but now it accepts redis syntax
        String[] parts = new String[count];
        for (int i = 0; i < count; i++) {
            String lengthLine = reader.readLine(); //Works as turning a page so lengthLine and content reads two different lines. They read until \r\n
            String content = reader.readLine();
            parts[i] = content;
        }

    
        //validering og kjøring av store metoder inkludert implementering av REDIS human readable syntax
        if (parts[0].equals("SET")) {
            if (parts.length < 3) {
                pw.print("-ERROR: SET requires a key and a value\r\n");
                pw.flush();
            } else {
                store.setValue(parts[1], parts[2]);
                pw.print("+OK\r\n");
                pw.flush();
            }
        } else if (parts[0].equals("GET")) {
            if (parts.length < 2) {
                pw.print("-ERROR: GET requires a key\r\n");
                pw.flush();
            } else {
                String value = store.getValue(parts[1]);
                if (value == null) {
                    pw.print("$-1\r\n");
                    pw.flush();
                } else {
                    String wrapped = "$" + value.length() + "\r\n" + value + "\r\n"; //Implementing REDIS
                pw.print(wrapped);
                pw.flush();
                }
            }
        } else if (parts[0].equals("DEL")) {
            if (parts.length < 2) {
                pw.print("-ERROR: DEL requires a key\r\n");
                pw.flush();
            } else {
                store.deleteValue(parts[1]);
                pw.print("+OK\r\n");
                pw.flush();
            }
        } else {
            pw.print("-ERROR: Unknown command\r\n");
            pw.flush();
        }
        }
        } 
        catch (Exception e) {
            System.out.println("Thread threw an exception!");
        } 
        finally {
            try {
                //.close ends the connection with the client so realeses everything tied to it.
                client.close(); 
            } 
            catch (Exception e) {
            System.out.println("client.close threw an exception");
            }
        }

        }).start();
    }
    }
}
