import java.util.concurrent.ConcurrentHashMap;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;

public class Store {

    private ConcurrentHashMap<String,ValueEntry> hashmap = new ConcurrentHashMap<>();

    public Store() {
        //when store gets created the log.txt gets created
        File file = new File("log.txt");
        if (!file.exists()) {
            return;
        }
        //Lese linje for linje av log.txt og putter inn verdiar for set i hashmap og remove dersom det er en del kommando
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split(" ", 3);
                if (parts[0].equals("SET")) {
                    hashmap.put(parts[1], new ValueEntry(parts[2], null));
                } else if (parts[0].equals("DEL")) {
                    hashmap.remove(parts[1]);
                }
            }
        } catch (IOException e) {
            System.out.println("IO exception while replaying log");
        }
    }

    public static void main(String[] args ) {
        
        Store store = new Store();
        store.setValue("temp", "hello", 2);
        System.out.println(store.getValue("temp")); //should still be "hello" here
        try {
            Thread.sleep(4000); //Waiting for key to expire
            System.out.println(store.getValue("temp"));
        } catch (InterruptedException e) {
            System.out.println("InterruptedException");
        }
    }

    public String getValue(String key) {
        ValueEntry entry = hashmap.get(key);
        if (entry == null) { // Need to check if entry == null first because we can't ask a null for .expiresAt field as it will give NullPointerException
            return null;
        } else if (entry.expiresAt != null && System.currentTimeMillis() > entry.expiresAt) { 
            deleteValue(key);
            return null;
        } else {
            return entry.value;
        }
    }

    public void setValue(String key, String value) {
        hashmap.put(key, new ValueEntry(value, null));

        //Skrive til ein log for kvar kommando
        try (FileWriter fw = new FileWriter("log.txt", true)){
            fw.write("SET " + key + " " + value + "\n");
        } catch (IOException e) {
            System.out.println("IO exception while setting value");
        }
    }

     public void setValue(String key, String value, long ttlSeconds) { //overload method (identical method with more or less parameteres)
        long expiresAt = System.currentTimeMillis() + ttlSeconds * 1000;
        hashmap.put(key, new ValueEntry(value, expiresAt));

        //Skrive til ein log for kvar kommando
        try (FileWriter fw = new FileWriter("log.txt", true)){
            fw.write("SET " + key + " " + value + "\n");
        } catch (IOException e) {
            System.out.println("IO exception while setting value");
        }
     }

    public void deleteValue(String key) {
            hashmap.remove(key);

        //Try-with resources and logging of each command. Putting filewriter into the paranthesis instead of inside the try-with it will close automatically so you dont ahve to write fw.close() yourself.
        try (FileWriter fw = new FileWriter("log.txt", true)){
            fw.write("DEL " + key + "\n");
        } catch (IOException e) {
            System.out.println("IO exception while deleting value");
        }
    }

}
