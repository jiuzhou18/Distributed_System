import java.rmi.Naming;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.server.UnicastRemoteObject;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class IndexServer extends UnicastRemoteObject implements IndexI{
	//Implementation of Index Sever.
	
	//Use ConcurrentHashMap synchronously save data from multiple peers, and it's safe for threads.
	//The Map peerdatamap record the file names for each peer:
	//The key is peer IP, and the value is all the files names on the peer.
	private static ConcurrentHashMap<String, ArrayList<String>> peerdatamap = 
			new ConcurrentHashMap<String, ArrayList<String>>();
	private static String ServerUrl = "rmi://127.0.0.1/IndexServer";
	
	//Explicit constructor for this Class. (RMI required)
	public IndexServer() throws RemoteException {
        super();
    }

	//Main method is used to run the server on the ServerUrl on port 1099.
	//The RMI Server will automatically create threads for calls from each peer.
	public static void main(String[] args) {
		try {
			System.out.println("start Index Server ...");
			IndexServer server = new IndexServer();
			System.out.println("RMI registry at port 1099.");
			LocateRegistry.createRegistry(1099); 
			Naming.rebind(ServerUrl, server); 
			System.out.println("Bind Succussfully.");
			System.out.println("Waitting calls for clients...\n");
		} catch (Exception e) {
			System.out.println("IndexServer Error: " + e);
		}
	}
	
	//Implement registry method required by assignment.
	//Record the peer IP and its files on the peerdatamap.
	//If the files is null, it means the peer is exiting and the IndexServer will remove this ip from peerdatamap.
	public void registry(String ip, ArrayList<String> files)throws RemoteException{
		System.out.println("Peer with IP: \""+ ip + "\" request registering.");
		if(files == null){
			peerdatamap.remove(ip);
			System.out.println("Peer with IP: \""+ ip + "\" exit.\n");
			return;
		}
		System.out.print("Peer with IP: \""+ ip + "\" register these files:");
		for(int i=0; i<files.size(); i++){
			if(i%5==0) System.out.println();
			System.out.print(files.get(i).concat("\t"));
		}
		peerdatamap.put(ip, files);
		System.out.println("\nPeer with IP: \""+ ip + "\" registered successfully.\n");
	}
	
	//Implement lookup method required by assignment.
	//Look up which peer contains the filename among the peerdatamap and save its IP to ArrayLIst "iplist".
	public ArrayList<String> lookup(String filename)throws RemoteException{
		System.out.println("A peer requset looking up file " + filename +" .");
		ArrayList<String> iplist = new ArrayList<>();
		for(Map.Entry<String, ArrayList<String>> e: peerdatamap.entrySet()){
			for(String file : (ArrayList<String>)e.getValue()){
				if(file.equals(filename)){
					String ip = (String) e.getKey();
					iplist.add(ip);
				}
			}
		}
		System.out.println("Searching for file " + filename +" compelete.");
		return iplist;
	}

}
