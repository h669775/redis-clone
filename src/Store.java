import java.util.concurrent.ConcurrentHashMap;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;

public class Store {

    private ConcurrentHashMap<String,String> hashmap = new ConcurrentHashMap<>();

    public Store() {
        File file = new File("log.txt");
        if (!file.exists()) {
            return;
        }
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split(" ", 3);
                if (parts[0].equals("SET")) {
                    hashmap.put(parts[1], parts[2]);
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
        store.setValue("ola", "22 år");
        store.setValue("emil", "19 år");
        System.out.println(store.getValue("ola"));
        System.out.println(store.getValue("emil"));
        store.deleteValue("emil");
        System.out.println(store.getValue("emil"));
    }

    public String getValue(String key) {
        return hashmap.get(key); 
    }

    public void setValue(String key, String value) {
        hashmap.put(key, value);

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
