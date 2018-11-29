/**
 * <p>Title: CPSC 433/533 Programming Assignment</p>
 *
 * <p>Description: Fishnet TCP manager</p>
 *
 * <p>Copyright: Copyright (c) 2006</p>
 *
 * <p>Company: Yale University</p>
 *
 * @author Hao Wang
 * @version 1.0
 */
import java.util.*;

public class TCPManager {
    private Node node;
    private int addr;
    private Manager manager;
    private SocketManager sockManager = new SocketManager();
    private Map<Integer, Map<String, TCPSock>> portMap;

    private static final byte dummy[] = new byte[0];

    public TCPManager(Node node, int addr, Manager manager) {
        this.node = node;
        this.addr = addr;
        this.manager = manager;
        this.portMap = new HashMap<Integer, Map<String, TCPSock>>();
    }

    /**
     * Start this TCP manager
     */
    public void start() {}

    /*
     * Begin socket API
     */

    /**
     * Create a socket
     *
     * @return TCPSock the newly created socket, which is not yet bound to
     *                 a local port
     */
    public TCPSock socket() {
        return new TCPSock(this, this.node);
    }

    /*
     * End Socket API
     */

    public void send(int srcPort, int destAddr, int destPort, 
        int type, int window, int seqNum, byte[] payload)
    {
        Transport transportPacket = new Transport(srcPort, destPort, type, 
            window, seqNum, payload);
        this.node.sendSegment(this.node.getAddr(),destAddr,Protocol.TRANSPORT_PKT, 
            transportPacket.pack());
    }

    public void receive(int srcAddr, int srcPort, int destAddr, int destPort, 
        Transport transport) 
    {
        TCPSock socket = findSocket(srcAddr,srcPort,destAddr,destPort);
        if(socket==null){
            node.logError("Cannot find socket for address "+srcAddr+"."+srcPort+"."+destAddr+"."+destPort);
            return;
        }
        socket.receive(srcAddr, srcPort, transport);
    }

    public void assignPort(TCPSock socket, int port){
        portMap.put(port, new HashMap<String,TCPSock>());
        portMap.get(port).put("",socket);
    }

    public void assignPort(TCPSock socket, int srcAddr, int srcPort, int myPort){
        String pairedAddr = srcAddr+"."+srcPort;
        Map<String,TCPSock> map = portMap.get(myPort);
    }

    public boolean checkAvailablePort(int port){
        if(portMap.containsKey(port) || port < 0 || port > Transport.MAX_PORT_NUM){
            return false;
        }
        else{
            return true;
        }
    }
    public boolean checkAvailableSocket(int srcAddr, int srcPort, int myPort){
        if(findSocket(srcAddr,srcPort,this.addr,myPort)!=null){
            return false;
        }
        else
            return true;
    }



    public void bindSocket(int srcAddr, int srcPort, int destPort, TCPSock socket){
        if(checkAvailablePort(destPort)){
            portMap.put(destPort, new HashMap<String,TCPSock>());
        }
        portMap.get(destPort).put(srcAddr+"."+srcPort,socket);
    }

    public boolean unbindSocket(int srcAddr, int srcPort, int destPort){
        String pairedAddr = srcAddr+"."+srcPort;
        if(checkAvailableSocket(srcAddr,srcPort,destPort)){
            return false;
        }
        else{
            Map<String,TCPSock> src2Socket = this.portMap.get(destPort);
            return src2Socket.remove(pairedAddr)!= null;
        }
    }

    public boolean unbindSocket(int destPort) {
        return this.portMap.remove(destPort) != null;
    }


    public TCPSock findSocket(int srcAddr, int srcPort, int destAddr, int destPort){
        if(destAddr!=this.addr) return null;
        Map<String,TCPSock> src2Socket = portMap.get(destPort);
        if(src2Socket==null) return null;
        TCPSock socket = src2Socket.get(srcAddr+"."+srcPort);
        if(socket==null)
            return src2Socket.get("");
        else return socket;
    }


    public void log(String output) {
        this.node.logOutput(output);
    }
    public void logError(String output) {
        this.node.logError(output);
    }



}
