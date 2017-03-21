

import java.rmi.Remote;
import java.rmi.RemoteException;

public interface PeerI extends Remote{
	
	//Remote Interface for Peer.
	byte[] retrieve(String filename) throws RemoteException;
	
}
