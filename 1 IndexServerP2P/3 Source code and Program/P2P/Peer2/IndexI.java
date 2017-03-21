import java.rmi.Remote;
import java.rmi.RemoteException;
import java.util.ArrayList;

public interface IndexI extends Remote{
	
	//Remote Interface for IndexSever.
	void registry(String ip, ArrayList<String> files)throws RemoteException;
	ArrayList<String> lookup(String filename)throws RemoteException;
	
}
