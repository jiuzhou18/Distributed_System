
import java.rmi.Remote;
import java.rmi.RemoteException;

public interface PeerI extends Remote{
	
	//Remote Interface for Peer.
	void query(String messageID, int TTL, String filename) throws RemoteException;
	void queryhit(String messageID, int TTL, String filename, String peerIP, int portnumber) throws RemoteException;
	byte[] obtain(String filename) throws RemoteException;
	
}
