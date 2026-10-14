import java.util.HashMap;

public class Store {

    private HashMap<String,String> hashmap = new HashMap<>();

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
    }

    public void deleteValue(String key) {
            
            hashmap.remove(key);
    }

}
