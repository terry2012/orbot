/* Copyright (c) 2009, Nathan Freitas, Orbot / The Guardian Project - http://openideals.com/guardian */
/* See LICENSE for licensing information */
package org.torproject.android.service;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

import net.freehaven.tor.control.ConfigEntry;
import net.freehaven.tor.control.EventHandler;
import net.freehaven.tor.control.TorControlConnection;

import org.torproject.android.AppManager;
import org.torproject.android.Orbot;
import org.torproject.android.R;

import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.IBinder;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.preference.PreferenceManager;
import android.util.Log;

public class TorService extends Service implements TorServiceConstants, Runnable, EventHandler
{
	
	
	private static int currentStatus = STATUS_READY;
		
	private TorControlConnection conn = null;
	
	private static TorService _torInstance;
	
	private static final int NOTIFY_ID = 1;
	
	private static final int MAX_START_TRIES = 3;

    private ArrayList<String> configBuffer = null;
    
    private boolean hasRoot = false;
    
    /** Called when the activity is first created. */
    public void onCreate() {
    	super.onCreate();
       
    	Log.i(TAG,"TorService: onCreate");
    	
    	checkTorBinaries();
    	
    	findExistingProc ();
    	
    	_torInstance = this;

    	hasRoot = TorServiceUtils.hasRoot();
    	 
    }
    
    private boolean findExistingProc ()
    {
    	 int procId = TorServiceUtils.findProcessId(TorServiceConstants.TOR_BINARY_INSTALL_PATH);

 		if (procId != -1)
 		{
 			Log.i(TAG,"Found existing Tor process");
 			
            sendCallbackMessage ("found existing Tor process...");

 			try {
 				currentStatus = STATUS_CONNECTING;
				
 				initControlConnection();
				
				
				currentStatus = STATUS_ON;
				
				return true;
 						
			} catch (RuntimeException e) {
				Log.i(TAG,"Unable to connect to existing Tor instance,",e);
				currentStatus = STATUS_OFF;
				this.stopTor();
				
			} catch (Exception e) {
				Log.i(TAG,"Unable to connect to existing Tor instance,",e);
				currentStatus = STATUS_OFF;
				this.stopTor();
				
			}
 		}
 		
 		return false;
    	 
    }
    

    /* (non-Javadoc)
	 * @see android.app.Service#onLowMemory()
	 */
	public void onLowMemory() {
		super.onLowMemory();
		
		Log.i(TAG, "Low Memory Called");
		
	}


	/* (non-Javadoc)
	 * @see android.app.Service#onUnbind(android.content.Intent)
	 */
	public boolean onUnbind(Intent intent) {
		
		Log.i(TAG, "onUnbind Called: " + intent.getAction());
		
		
		return super.onUnbind(intent);
		
		
	}

	public int getTorStatus ()
    {
    	
    	return currentStatus;
    	
    }
	
   
	private void showToolbarNotification (String notifyMsg, int icon)
	{
		
		NotificationManager mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

		
		CharSequence tickerText = notifyMsg;
		long when = System.currentTimeMillis();

		Notification notification = new Notification(icon, tickerText, when);
		
		Context context = getApplicationContext();
		CharSequence contentTitle = getString(R.string.app_name);
		CharSequence contentText = notifyMsg;
		
		Intent notificationIntent = new Intent(this, Orbot.class);
		PendingIntent contentIntent = PendingIntent.getActivity(this, 0, notificationIntent, 0);

		notification.setLatestEventInfo(context, contentTitle, contentText, contentIntent);


		mNotificationManager.notify(NOTIFY_ID, notification);


	}
    
    /* (non-Javadoc)
	 * @see android.app.Service#onRebind(android.content.Intent)
	 */
	public void onRebind(Intent intent) {
		super.onRebind(intent);
		
		 Log.i(TAG,"on rebind");
	}


	/* (non-Javadoc)
	 * @see android.app.Service#onStart(android.content.Intent, int)
	 */
	public void onStart(Intent intent, int startId) {
		super.onStart(intent, startId);
		
	}
	 
	public void run ()
	{
		boolean isRunning = _torInstance.findExistingProc ();
		
		if (!isRunning)
		{
	     try
	     {
		   initTor();
		   
		   
	     }
	     catch (Exception e)
	     {
	    	 currentStatus = STATUS_OFF;
	    	 this.showToolbarNotification(getString(R.string.status_disabled), R.drawable.tornotification);
	    	 Log.i(TAG,"Unable to start Tor: " + e.getMessage(),e);
	     }
		}
	}

	
    public void onDestroy ()
    {
    	super.onDestroy();
    	
    	  // Unregister all callbacks.
        mCallbacks.kill();
      
        
    	Log.i(TAG,"onDestroy called");
	     
    	stopTor();
    }
    
    private void stopTor ()
    {
    	currentStatus = STATUS_OFF;
    	
    		
    	sendCallbackMessage("Web proxy shutdown");
    	
		killTorProcess ();
				
		currentStatus = STATUS_READY;
    	
	
		showToolbarNotification (getString(R.string.status_disabled),R.drawable.tornotificationoff);
    	sendCallbackMessage(getString(R.string.status_disabled));

    	setupTransProxy(false);
    }
    
 
   
    
    public void reloadConfig ()
    {
    	try
		{
	    	if (conn == null)
			{
				initControlConnection ();
			}
		
			if (conn != null)
			{
				 conn.signal("RELOAD");
			}
		}
    	catch (Exception e)
    	{
    		Log.i(TAG,"Unable to reload configuration",e);
    	}
    }
    
    private void killTorProcess ()
    {
		
    	if (conn != null)
		{
			try {
				logNotice("sending SHUTDOWN signal to Tor process");
			//	conn.shutdownTor(arg0)
				conn.signal("SHUTDOWN");
			} catch (Exception e) {
				Log.i(TAG,"error shutting down Tor via connection",e);
			}
			conn = null;
		}
    	
    	StringBuilder log = new StringBuilder();
    	
		int procId = TorServiceUtils.findProcessId(TorServiceConstants.TOR_BINARY_INSTALL_PATH);

		while (procId != -1)
		{
			
			logNotice("Found Tor PID=" + procId + " - killing now...");
			
			String[] cmd = { SHELL_CMD_KILL + ' ' + procId + "" };
			TorServiceUtils.doShellCommand(cmd,log, false, false);

			procId = TorServiceUtils.findProcessId(TorServiceConstants.TOR_BINARY_INSTALL_PATH);
		}
		
		procId = TorServiceUtils.findProcessId(TorServiceConstants.PRIVOXY_INSTALL_PATH);

		while (procId != -1)
		{
			
			Log.i(TAG,"Found Privoxy PID=" + procId + " - killing now...");
			String[] cmd = { SHELL_CMD_KILL + ' ' + procId + "" };

			TorServiceUtils.doShellCommand(cmd,log, false, false);

			procId = TorServiceUtils.findProcessId(TorServiceConstants.PRIVOXY_INSTALL_PATH);
		}
		
    }
   
    private void logNotice (String msg)
    {

    	Log.i(TAG, msg);
    	
    	sendCallbackMessage('>' + msg);
		
    }
    
    private boolean checkTorBinaries ()
    {

		boolean torBinaryExists = new File(TOR_BINARY_INSTALL_PATH).exists();
		boolean privoxyBinaryExists = new File(PRIVOXY_INSTALL_PATH).exists();

		if (!(torBinaryExists && privoxyBinaryExists))
		{
			killTorProcess ();
			
			TorBinaryInstaller installer = new TorBinaryInstaller(); 
			installer.start(true);
		
			torBinaryExists = new File(TOR_BINARY_INSTALL_PATH).exists();
			privoxyBinaryExists = new File(PRIVOXY_INSTALL_PATH).exists();
			
    		if (torBinaryExists && privoxyBinaryExists)
    		{
    			logNotice(getString(R.string.status_install_success));
    	
    			showToolbarNotification(getString(R.string.status_install_success), R.drawable.tornotification);
    		
    		}
    		else
    		{
    		
    			logNotice(getString(R.string.status_install_fail));

    			showAlert(getString(R.string.title_error),getString(R.string.status_install_fail));
    		
    			return false;
    		}
    		
		}
		
		StringBuilder log = new StringBuilder ();
		
		logNotice("Setting permission on Tor binary");
		String[] cmd1 = {SHELL_CMD_CHMOD + ' ' + CHMOD_EXE_VALUE + ' ' + TOR_BINARY_INSTALL_PATH};
		TorServiceUtils.doShellCommand(cmd1, log, false, true);
		
		logNotice("Setting permission on Privoxy binary");
		String[] cmd2 = {SHELL_CMD_CHMOD + ' ' + CHMOD_EXE_VALUE + ' ' + PRIVOXY_INSTALL_PATH};
		TorServiceUtils.doShellCommand(cmd2, log, false, true);
				
		return true;
    }
    
    public void initTor () throws Exception
    {
    	   // android.os.Debug.waitForDebugger();
    	
    		currentStatus = STATUS_CONNECTING;

    		logNotice(getString(R.string.status_starting_up));
    		
    		sendCallbackMessage(getString(R.string.status_starting_up));
    		
    		killTorProcess ();
    		
    		checkTorBinaries ();

        	
    		
    		new Thread()
    		{
    			public void run ()
    			{
    				try {
    		    		runPrivoxyShellCmd();

					} catch (Exception e) {
						currentStatus = STATUS_OFF;
				    	 Log.i(TAG,"Unable to start Privoxy: " + e.getMessage(),e);
	    			    sendCallbackMessage("Unable to start Privoxy: " + e.getMessage());

					} 
    			}
    		}.start();
    		
    		new Thread()
    		{
    			public void run ()
    			{
    				try {
    		    		runTorShellCmd();
    		    		
    		    		setupTransProxy(true);

    				} catch (Exception e) {
    			    	Log.i(TAG,"Unable to start Tor: " + e.getMessage(),e);	
    			    	sendCallbackMessage("Unable to start Tor: " + e.getMessage());
    			    	stopTor();
    			    } 
    			}
    		}.start();
    		
    		
    }
    
    private void runTorShellCmd() throws Exception
    {
    	StringBuilder log = new StringBuilder();
		
		Log.i(TAG,"Starting tor process");
		
		String[] torCmd = {TOR_BINARY_INSTALL_PATH + ' ' + TOR_COMMAND_LINE_ARGS};
		TorServiceUtils.doShellCommand(torCmd, log, false, false);
	
		Thread.sleep(1000);
		int procId = TorServiceUtils.findProcessId(TorServiceConstants.TOR_BINARY_INSTALL_PATH);

		int attempts = 0;
		
		while (procId == -1 && attempts < MAX_START_TRIES)
		{
			log = new StringBuilder();
			
			logNotice(torCmd[0]);
			
			TorServiceUtils.doShellCommand(torCmd, log, false, false);
			procId = TorServiceUtils.findProcessId(TorServiceConstants.TOR_BINARY_INSTALL_PATH);
			
			if (procId == -1)
			{
				sendCallbackMessage("Couldn't start Tor process...\n" + log.toString());
				Thread.sleep(1000);
				sendCallbackMessage(getString(R.string.status_starting_up));
				Thread.sleep(3000);
				attempts++;
			}
			
			logNotice(log.toString());
		}
		
		if (procId == -1)
		{
			throw new Exception ("Unable to start Tor");
		}
		else
		{
		
			logNotice("Tor process id=" + procId);
			
			showToolbarNotification(getString(R.string.status_starting_up), R.drawable.tornotification);
			
			initControlConnection ();
	    }
    }
    
    private void runPrivoxyShellCmd () throws Exception
    {
			int privoxyProcId = TorServiceUtils.findProcessId(TorServiceConstants.PRIVOXY_INSTALL_PATH);

			StringBuilder log = null;
			
			int attempts = 0;
			
    		while (privoxyProcId == -1 && attempts < MAX_START_TRIES)
    		{
    			log = new StringBuilder();
    			
    			String[] cmds = 
    			{ PRIVOXY_INSTALL_PATH + " " + PRIVOXY_COMMAND_LINE_ARGS };
    			
    			logNotice (cmds[0]);
    			
    			TorServiceUtils.doShellCommand(cmds, log, false, true);
    			
    			//wait one second to make sure it has started up
    			Thread.sleep(1000);
    			
    			privoxyProcId = TorServiceUtils.findProcessId(TorServiceConstants.PRIVOXY_INSTALL_PATH);
    			
    			if (privoxyProcId == -1)
    			{
    				logNotice("Couldn't start Privoxy process... retrying...\n" + log);
    				Thread.sleep(3000);
    				attempts++;
    			}
    			
    			logNotice(log.toString());
    		}
    		
			sendCallbackMessage("Privoxy is running on port: " + PORT_HTTP);
			Thread.sleep(100);
			
    		logNotice("Privoxy process id=" + privoxyProcId);
			
    		
    		
    }
    
    /*
	public String generateHashPassword ()
	{
		
		PasswordDigest d = PasswordDigest.generateDigest();
	      byte[] s = d.getSecret(); // pass this to authenticate
	      String h = d.getHashedPassword(); // pass this to the Tor on startup.

		return null;
	}*/
	
	public void initControlConnection () throws Exception, RuntimeException
	{
			while (true)
			{
				try
				{
					Log.i(TAG,"Connecting to control port: " + TOR_CONTROL_PORT);
					
					String baseMessage = getString(R.string.tor_process_connecting);
					sendCallbackMessage(baseMessage);
					
					Socket s = new Socket(IP_LOCALHOST, TOR_CONTROL_PORT);
			        conn = TorControlConnection.getConnection(s);
			      //  conn.authenticate(new byte[0]); // See section 3.2
			        
					sendCallbackMessage(getString(R.string.tor_process_connecting_step2));

			        Log.i(TAG,"SUCCESS connected to control port");
			        
			        File fileCookie = new File(TOR_CONTROL_AUTH_COOKIE);
			        byte[] cookie = new byte[(int)fileCookie.length()];
			        new FileInputStream(new File(TOR_CONTROL_AUTH_COOKIE)).read(cookie);
			        conn.authenticate(cookie);
			        		
			        Log.i(TAG,"SUCCESS authenticated to control port");
			        
					sendCallbackMessage(getString(R.string.tor_process_connecting_step2) + getString(R.string.tor_process_connecting_step3));

			        addEventHandler();
			        
			        if (configBuffer != null)
			        {
			        	conn.setConf(configBuffer);
			        	//conn.saveConf();
			        	configBuffer = null;
			        }
			        
			        break; //don't need to retry
				}
				catch (Exception ce)
				{
					conn = null;
					Log.i(TAG,"Attempt: Error connecting to control port: " + ce.getLocalizedMessage(),ce);
					
					sendCallbackMessage(getString(R.string.tor_process_connecting_step4));

					Thread.sleep(1000);
					
					
				}	
			}
		
		

	}
	
	
	/*
	private void getTorStatus () throws IOException
	{
		try
		{
			 
			if (conn != null)
			{
				 // get a single value.
			      
			       // get several values
			       
			       if (currentStatus == STATUS_CONNECTING)
			       {
				       //Map vals = conn.getInfo(Arrays.asList(new String[]{
				         // "status/bootstrap-phase", "status","version"}));
			
				       String bsPhase = conn.getInfo("status/bootstrap-phase");
				       Log.i(TAG, "bootstrap-phase: " + bsPhase);
				       
				       
			       }
			       else
			       {
			    	 //  String status = conn.getInfo("status/circuit-established");
			    	 //  Log.i(TAG, "status/circuit-established=" + status);
			       }
			}
		}
		catch (Exception e)
		{
			Log.i(TAG, "Unable to get Tor status from control port");
			currentStatus = STATUS_UNAVAILABLE;
		}
		
	}*/
	
	
	public void addEventHandler () throws IOException
	{
	       // We extend NullEventHandler so that we don't need to provide empty
	       // implementations for all the events we don't care about.
	       // ...
        Log.i(TAG,"adding control port event handler");

		conn.setEventHandler(this);
	    
		conn.setEvents(Arrays.asList(new String[]{
	          "ORCONN", "CIRC", "NOTICE", "WARN", "ERR"}));
	      // conn.setEvents(Arrays.asList(new String[]{
	        //  "DEBUG", "INFO", "NOTICE", "WARN", "ERR"}));

	    Log.i(TAG,"SUCCESS added control port event handler");
	    
	    

	}
	
		/**
		 * Returns the port number that the HTTP proxy is running on
		 */
		public int getHTTPPort() throws RemoteException {
			return TorServiceConstants.PORT_HTTP;
		}

		/**
		 * Returns the port number that the SOCKS proxy is running on
		 */
		public int getSOCKSPort() throws RemoteException {
			return TorServiceConstants.PORT_SOCKS;
		}


		
		
		public int getProfile() throws RemoteException {
			//return mProfile;
			return PROFILE_ON;
		}
		
		public void setTorProfile(int profile)  {
			
			if (profile == PROFILE_ON)
			{
 				currentStatus = STATUS_CONNECTING;
	            sendCallbackMessage ("starting...");

	            new Thread(_torInstance).start();

			}
			else
			{
				currentStatus = STATUS_OFF;
	            sendCallbackMessage ("shutting down...");
	            
				_torInstance.stopTor();

			}
		}



	public void message(String severity, String msg) {
		
          Log.i(TAG, "[Tor Control Port] " + severity + ": " + msg);
          
          if (msg.indexOf(TOR_CONTROL_PORT_MSG_BOOTSTRAP_DONE)!=-1)
          {
        	  currentStatus = STATUS_ON;
        	  showToolbarNotification (getString(R.string.status_activated),R.drawable.tornotification);
  			  
          }
         
          sendCallbackMessage (msg);
             
	}

	private void showAlert(String title, String msg)
	{
		 
		 new AlertDialog.Builder(this)
         .setTitle(title)
         .setMessage(msg)
         .setPositiveButton(android.R.string.ok, null)
         .show();
	}

	public void newDescriptors(List<String> orList) {
		
	}


	public void orConnStatus(String status, String orName) {
		
		StringBuilder sb = new StringBuilder();
		sb.append("orConnStatus (");
		sb.append((orName) );
		sb.append("): ");
		sb.append(status);
		
		logNotice(sb.toString());
	}


	public void streamStatus(String status, String streamID, String target) {
		
		StringBuilder sb = new StringBuilder();
		sb.append("StreamStatus (");
		sb.append((streamID));
		sb.append("): ");
		sb.append(status);
		
		logNotice(sb.toString());

		
	}


	public void unrecognized(String type, String msg) {
		
		StringBuilder sb = new StringBuilder();
		sb.append("Message (");
		sb.append(type);
		sb.append("): ");
		sb.append(msg);
		
		logNotice(sb.toString());

		
	}

	public void bandwidthUsed(long read, long written) {
		sendCallbackMessage ("bandwidth used: read=" + read + " written=" + written);
		
		StringBuilder sb = new StringBuilder();
		sb.append("Bandwidth used: ");
		sb.append(read/1000);
		sb.append("kb read / ");
		sb.append(written/1000);
		sb.append("kb written");
		
		logNotice(sb.toString());

	}

	public void circuitStatus(String status, String circID, String path) {
		
		StringBuilder sb = new StringBuilder();
		sb.append("Circuit (");
		sb.append((circID));
		sb.append("): ");
		sb.append(status);
		sb.append("; ");
		sb.append(path);
		
		logNotice(sb.toString());

		
	}
	
	private Intent launchContext = null;
	
    public IBinder onBind(Intent intent) {
        // Select the interface to return.  If your service only implements
        // a single interface, you can just return it here without checking
        // the Intent.
        if (ITorService.class.getName().equals(intent.getAction())) {
            return mBinder;
        }
      
        return null;
    }
	
    /**
     * This is a list of callbacks that have been registered with the
     * service.  Note that this is package scoped (instead of private) so
     * that it can be accessed more efficiently from inner classes.
     */
    final RemoteCallbackList<ITorServiceCallback> mCallbacks
            = new RemoteCallbackList<ITorServiceCallback>();


    /**
     * The IRemoteInterface is defined through IDL
     */
    private final ITorService.Stub mBinder = new ITorService.Stub() {
    	
        public void registerCallback(ITorServiceCallback cb) {
            if (cb != null) mCallbacks.register(cb);
        }
        public void unregisterCallback(ITorServiceCallback cb) {
            if (cb != null) mCallbacks.unregister(cb);
        }
        public int getStatus () {
        	return getTorStatus();
        }
        
        public void setProfile (int profile)
        {
        	setTorProfile(profile);
        	
        }
        
        public String getConfiguration (String name)
        {
        	try
        	{
	        	if (conn != null)
	        	{
	        		StringBuffer result = new StringBuffer();
	        		
	        		List<ConfigEntry> listCe = conn.getConf(name);
	        		
	        		Iterator<ConfigEntry> itCe = listCe.iterator();
	        		ConfigEntry ce = null;
	        		
	        		while (itCe.hasNext())
	        		{
	        			ce = itCe.next();
	        			
	        			result.append(ce.key);
	        			result.append(' ');
	        			result.append(ce.value);
	        			result.append('\n');
	        		}
	        		
	   	       		return result.toString();
	        	}
        	}
        	catch (IOException ioe)
        	{
        		Log.e(TAG, "Unable to update Tor configuration", ioe);
        		logNotice("Unable to update Tor configuration: " + ioe.getMessage());
        	}
        	
        	return null;
        }
        /**
         * Set configuration
         **/
        public boolean updateConfiguration (String name, String value, boolean saveToDisk)
        {
        	try
        	{
	        	if (conn != null)
	        	{
	        		conn.setConf(name, value);
	   	       
	        		if (saveToDisk)
	        		{
	   	       			// Flush the configuration to disk.
	   	       			//conn.saveConf(); //NF 22/07/10 this is crashing right now
	        		}
	        		
	   	       		return true;
	        	}
	        	else
	        	{
	        		if (configBuffer == null)
	        			configBuffer = new ArrayList<String>();
	        		
	        		configBuffer.add(name + ' ' + value);
	        	}
        	}
        	catch (IOException ioe)
        	{
        		Log.e(TAG, "Unable to update Tor configuration", ioe);
        		logNotice("Unable to update Tor configuration: " + ioe.getMessage());
        	}
        	
        	return false;
        }
         
	    public boolean saveConfiguration ()
	    {
	    	try
        	{
	        	if (conn != null)
	        	{
	        		
	   	       
	   	       		// Flush the configuration to disk.
	        		//this is doing bad things right now NF 22/07/10
	   	       		//conn.saveConf();
	
	   	       		return true;
	        	}
        	}
        	catch (Exception ioe)
        	{
        		Log.e(TAG, "Unable to update Tor configuration", ioe);
        		logNotice("Unable to update Tor configuration: " + ioe.getMessage());
        	}
        	
        	return false;
        	
	    }
    };
    
    private ArrayList<String> callbackBuffer = new ArrayList<String>();
    
    private void sendCallbackMessage (String newStatus)
    {
    	 
        // Broadcast to all clients the new value.
        final int N = mCallbacks.beginBroadcast();
        

    	callbackBuffer.add(newStatus);
    	
        if (N > 0)
        {
        
        	Iterator<String> it = callbackBuffer.iterator();
        	String status = null;
        	
        	while (it.hasNext())
        	{
        		status = it.next();
        		
		        for (int i=0; i<N; i++) {
		            try {
		                mCallbacks.getBroadcastItem(i).statusChanged(status);
		                
		                
		            } catch (RemoteException e) {
		                // The RemoteCallbackList will take care of removing
		                // the dead object for us.
		            }
		        }
        	}
	        
	        callbackBuffer.clear();
        }
        
        mCallbacks.finishBroadcast();
    }
    
    private void setupTransProxy (boolean enabled)
	{
    	
		SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
		
		boolean enableTransparentProxy = prefs.getBoolean("pref_transparent", false);
		boolean transProxyAll = prefs.getBoolean("pref_transparent_all", false);
	
    	logNotice ("Transparent Proxying: " + enableTransparentProxy);
    	
		if (enabled)
		{
		
	
			if (hasRoot && enableTransparentProxy)
			{
				
				TorTransProxy.setDNSProxying();
				TorTransProxy.setTransparentProxyingByApp(this,AppManager.getApps(this),transProxyAll);
				
			}
			else
			{
				TorTransProxy.purgeNatIptables();

			}
		}
		else
		{
			if (hasRoot)
			{
				TorTransProxy.purgeNatIptables();
			}
		}
	}
}
