/**
 * <p>Title: CPSC 433/533 Programming Assignment</p>
 *
 * <p>Description: Fishnet socket implementation</p>
 *
 * <p>Copyright: Copyright (c) 2006</p>
 *
 * <p>Company: Yale University</p>
 *
 * @author Hao Wang
 * @version 1.0
 */
import java.nio.*;
import java.util.*;
import java.util.concurrent.*;

public class TCPSock {
    // TCP socket states
    enum State {
        // protocol states
        CLOSED,
        LISTEN,
        SYN_SENT,
        ESTABLISHED,
        SHUTDOWN // close requested, FIN not sent (due to unsent data in queue)
    }

    enum Role {
        UNDETERMINED,
        SENDER,
        RECEIVER,
        LISTENER
    }

    private static final boolean LOG = true;
    private static final int DEFAULT_TIMEOUT = 1000;

    private State state;
    private Role role;
    private Node node;
    private TCPManager tcpMan;

    private int myPort;
    private int destAddr;
    private int destPort;
    private boolean bound = false; //if bind to a port
    private ArrayBlockingQueue<TCPSock> socketQueue; 
    private int seqNum;
    private ByteBuffer dataBuffer = ByteBuffer.allocate(DEFAULT_BUFFER_SZ);

    public static final int DEFAULT_BUFFER_SZ = 65536;


    public TCPSock(TCPManager manager, Node node) {
        this.node = node;
        this.tcpMan = manager;
        this.seqNum = 0;
        this.role = Role.UNDETERMINED;
    }

    private TCPSock(TCPManager manager, Node node, int destAddr, int destPort, int myPort,  
        Transport transport) 
    {
        this(manager, node);
        this.seqNum = transport.getSeqNum()+1;
        state = State.ESTABLISHED;
        role = Role.RECEIVER;
        this.myPort = myPort;
        this.destAddr = destAddr;
        this.destPort = destPort;
        sendACK();
    }

    /*
     * The following are the socket APIs of TCP transport service.
     * All APIs are NON-BLOCKING.
     */

    /**
     * Bind a socket to a local port
     *
     * @param localPort int local port number to bind the socket to
     * @return int 0 on success, -1 otherwise
     */
    public int bind(int localPort) {
        logOutput("bind");
        if(bound) return -1;
        if(!tcpMan.checkAvailablePort(localPort)) return -1;
        tcpMan.assignPort(this,localPort);
        this.myPort = localPort;
        bound = true;
        return 0;
    }

    /**
     * Listen for connections on a socket
     * @param backlog int Maximum number of pending connections
     * @return int 0 on success, -1 otherwise
     */
    public int listen(int backlog) {
        logOutput("listen");
        if(!bound) return -1;
        this.state = State.LISTEN;
        this.role = Role.LISTENER;
        this.socketQueue = new ArrayBlockingQueue<>(backlog);
        return 0;
    }

    /**
     * Accept a connection on a socket
     *
     * @return TCPSock The first established connection on the request queue
     */
    public TCPSock accept() {
        if (this.role != Role.LISTENER) return null;
        return this.socketQueue.poll();
    }

    public boolean isConnectionPending() {
        return (state == State.SYN_SENT);
    }

    public boolean isClosed() {
        return (state == State.CLOSED);
    }

    public boolean isConnected() {
        return (state == State.ESTABLISHED);
    }

    public boolean isClosurePending() {
        return (state == State.SHUTDOWN);
    }

    /**
     * Initiate connection to a remote socket
     *
     * @param destAddr int Destination node address
     * @param destPort int Destination port
     * @return int 0 on success, -1 otherwise
     */
    public int connect(int destAddr, int destPort) {
        logOutput("connect");
        if(!bound) return -1;
        this.destAddr = destAddr;
        this.destPort = destPort;
        send(Transport.SYN, 0, this.seqNum, new byte[0]);
        this.seqNum += 1;
        this.state = State.SYN_SENT;
        this.role = Role.SENDER;
        return 0;

    }

    /**
     * Initiate closure of a connection (graceful shutdown)
     */
    public void close() {
        if(this.role == Role.LISTENER || this.role == Role.UNDETERMINED)
            return;
        this.seqNum++;
        send(Transport.FIN,0,this.seqNum, new byte[0]);
        this.state = State.SHUTDOWN;
        logOutput("Sent FIN (" + this.seqNum + ")");
    }

    /**
     * Release a connection immediately (abortive shutdown)
     */
    public void release() {
        if(isClosed())
            return;
        this.state = State.CLOSED;
        if(this.role == Role.LISTENER){
            this.tcpMan.unbindSocket(this.destAddr,this.destPort,this.myPort);
            return;
        }
        else{
            this.tcpMan.unbindSocket(this.myPort);
        }

    }

    /**
     * Write to the socket up to len bytes from the buffer buf starting at
     * position pos.
     *
     * @param buf byte[] the buffer to write from
     * @param pos int starting position in buffer
     * @param len int number of bytes to write
     * @return int on success, the number of bytes written, which may be smaller
     *             than len; on failure, -1
     */
    public int write(byte[] buf, int pos, int len) {
        if(isClosed() || this.role!=Role.SENDER) return -1;
        len = Math.min(Transport.MAX_PAYLOAD_SIZE,len);
        int written = 0;
        byte[] payload = new byte[len];
        for (int i = 0; i < Math.min(len,buf.length-pos); i++ ) {
            payload[i] = buf[pos+i];
            written++;
        }
        send(Transport.DATA,0,this.seqNum,payload);

        return written;
    }

    /**
     * Read from the socket up to len bytes into the buffer buf starting at
     * position pos.
     *
     * @param buf byte[] the buffer
     * @param pos int starting position in buffer
     * @param len int number of bytes to read
     * @return int on success, the number of bytes read, which may be smaller
     *             than len; on failure, -1
     */
    public int read(byte[] buf, int pos, int len) {
        if(isClosed() || this.role!=Role.RECEIVER)
            return -1;
        dataBuffer.flip();
        int read = Math.min(dataBuffer.remaining(), len);
        dataBuffer.get(buf, pos, read);
        dataBuffer.compact();

        return read;
    }

    /*
     * End of socket API
     */

    public void send(int type, int window, int seqNum, byte[] payload){
        this.node.logOutput("Send from "+this.myPort+" to "+this.destAddr+"."+this.destPort + " Seq num: "+seqNum);
        this.tcpMan.send(this.myPort, this.destAddr, this.destPort, 
            type, window, seqNum, payload);
        this.seqNum += payload.length;
        switch(type){
            case Transport.DATA: 
                logOutput(".");
                logCode(".");

                // Method method = Callback.getMethod("timeout", this, null);
                // Callback cb = new Callback(method, this, null);
                // addTimer(seqNum,cb)

                break;
                // System.out.print(".");
            case Transport.SYN: 
                logOutput("S");
                logCode("S");
                break;
                // System.out.print("S");
            case Transport.FIN: 
                logOutput("F");
                logCode("F");
                break;
                // System.out.print("F");

        }
    }


    public void receive(int srcAddr, int srcPort, Transport transport){
        if(isClosed()) return;
        switch (transport.getType()) {
            case Transport.SYN:
                receiveSYN(srcAddr, srcPort, transport);
                break;

            case Transport.ACK:
                receiveACK(transport);
                break;

            case Transport.DATA:
                receiveDATA(transport);
                break;

            case Transport.FIN:
                receiveFIN(transport);
                break;
        }
    }


    public void receiveSYN(int srcAddr, int srcPort, Transport transport){
        logOutput("S");
        logCode("S");
        if(this.role == Role.RECEIVER){
            sendACK();
            return;
        }
        else{
            if(this.socketQueue.remainingCapacity() == 0){
                this.tcpMan.logError("Backlog full!");
                return;
            }
            TCPSock socket = new TCPSock(this.tcpMan, this.node, srcAddr, srcPort, this.myPort, transport);

            if(this.tcpMan.checkAvailableSocket(srcAddr,srcPort,this.myPort)){
                this.tcpMan.logError("Address unavailable!");
                return;
            }
            else{
                this.tcpMan.bindSocket(srcAddr,srcPort,this.myPort,socket);
            }
            
            try{
                this.socketQueue.add(socket);
            }
            catch(IllegalStateException e){
                socket.release();
                this.tcpMan.logError("Could not add to socket queue!");
            }
        }

    }

    public void receiveACK(Transport transport){
        if(this.role != Role.SENDER) return;
        receiveACKforSYN(transport);
        receiveACKforFIN(transport);

    }


    private void receiveACKforSYN(Transport transport){
        if(!isConnectionPending()) return;

        if(transport.getSeqNum()!=this.seqNum){
            this.node.logOutput("?");
            logCode("?");
            this.tcpMan.logError("SYN ACK seq number mismatch!");
        }
        else{
            this.state = State.ESTABLISHED;
            // client.setSendBase(client.getNextSeqNum() + 1);
            logOutput("Connected!");
            logOutput(":");
            logCode(":");
        }
    }

    private void receiveACKforFIN(Transport transport){
        if (!isClosurePending()) return;

        if(transport.getSeqNum()!=this.seqNum){
            this.node.logOutput("?");
            logCode("?");
            this.tcpMan.logError("FIN ACK seq number mismatch!");
        }
        else{
            release();
            // client.setSendBase(client.getNextSeqNum() + 1);
            logOutput("Closed!");
            logCode(":");
            this.node.logOutput(":");
        }    
    }


    private void receiveACKforData(Transport transport){
        if(this.role!=Role.SENDER || isClosurePending() || isConnectionPending()) 
            return;

    }


    private void receiveDATA(Transport transport){
        logOutput(".");   
        logCode(".");
        if(this.role != Role.RECEIVER) return;
        dataBuffer.put(transport.getPayload());

    }

    private void receiveFIN(Transport transport){
        logOutput("F");
        logCode("F");
        if(this.role != Role.RECEIVER) return;
        this.seqNum = transport.getSeqNum();
        sendACK();
        release();
    }

/**
LISTENER Socket API
**/
    private void sendACK(){
        send(Transport.ACK, 0, this.seqNum, new byte[0]);
    }

    private void logOutput(String output){
        if(LOG) this.node.logOutput(output);
    }

    private void logCode(String output){
        if(!LOG) System.out.print(output);
    }

    public void timeout(int seq){

    }

    private void addTimer(long deltaT, Callback cb) {
        try {
            this.node.getManager().addTimer(this.node.getAddress(), deltaT, cb);
        }catch(Exception e) {
            this.node.logError("Failed to add timer callback. Method Name: " +
                 "\nException: " + e);
        }
    }


}
