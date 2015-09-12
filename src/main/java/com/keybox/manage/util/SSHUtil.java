/**
 * Copyright 2013 Sean Kavanagh - sean.p.kavanagh6@gmail.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.keybox.manage.util;

import com.jcraft.jsch.Channel;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.ChannelShell;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.KeyPair;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpException;
import com.keybox.common.util.AppConfig;
import com.keybox.manage.db.*;
import com.keybox.manage.model.*;
import com.keybox.manage.task.SecureShellTask;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;

import java.io.*;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SSH utility class used to create public/private key for system and distribute authorized key files
 */
public class SSHUtil {


    private static Logger log = LoggerFactory.getLogger(SSHUtil.class);
    public static final boolean keyManagementEnabled = "true".equals(AppConfig.getProperty("keyManagementEnabled"));

	//system path to public/private key
	public static final String KEY_PATH = DBUtils.class.getClassLoader().getResource("keydb").getPath();

	//key type - rsa or dsa
	public static final String KEY_TYPE = AppConfig.getProperty("sshKeyType");
	public static final int KEY_LENGTH= StringUtils.isNumeric(AppConfig.getProperty("sshKeyLength")) ? Integer.parseInt(AppConfig.getProperty("sshKeyLength")) : 2048;

	//private key name
	public static final String PVT_KEY = KEY_PATH + "/id_" + KEY_TYPE;
	//public key name
	public static final String PUB_KEY = PVT_KEY + ".pub";

	public static final boolean dynamicKeys = AppConfig.getProperty("dynamicKeys").equals("true");
	
	public static final int SESSION_TIMEOUT = 60000;
	public static final int CHANNEL_TIMEOUT = 60000;
	
	public static final String SAVEFILE = "/tmp/tmp_authorized_keys_KeyBox";
	
	public static final String KEY_COMMENT = "KeyBox generated key pair from ";
	
	/**
	 * returns the system's public key
	 *
	 * @return system's public key
	 */
	public static String getPublicKey() {

		String publicKey = PUB_KEY;
		//check to see if pub/pvt are defined in properties
		if (StringUtils.isNotEmpty(AppConfig.getProperty("privateKey")) && StringUtils.isNotEmpty(AppConfig.getProperty("publicKey"))) {
			publicKey = AppConfig.getProperty("publicKey");
		}
		//read pvt ssh key
		File file = new File(publicKey);
		try {
			publicKey = FileUtils.readFileToString(file);
		} catch (Exception ex) {
			log.error(ex.toString(), ex);
		}
		return publicKey;
	}


	/**
	 * returns the system's public key
	 *
	 * @return system's public key
	 */
	public static String getPrivateKey() {

		String privateKey = PVT_KEY;
		//check to see if pub/pvt are defined in properties
		if (StringUtils.isNotEmpty(AppConfig.getProperty("privateKey")) && StringUtils.isNotEmpty(AppConfig.getProperty("publicKey"))) {
			privateKey = AppConfig.getProperty("privateKey");
		}

		//read pvt ssh key
		File file = new File(privateKey);
		try {
			privateKey = FileUtils.readFileToString(file);
		} catch (Exception ex) {
			log.error(ex.toString(), ex);
		}
		return privateKey;
	}

	/**
	 * generates system's public/private key par and returns passphrase
	 *
	 * @return passphrase for system generated key
	 */
	public static String keyGen() {
		//get passphrase cmd from properties file
		Map<String, String> replaceMap = new HashMap<String, String>();
		replaceMap.put("randomPassphrase", UUID.randomUUID().toString());

		String passphrase = AppConfig.getProperty("defaultSSHPassphrase", replaceMap);

		AppConfig.updateProperty("defaultSSHPassphrase", "${randomPassphrase}");

		return keyGen(passphrase);
	}

	/**
	 * delete SSH keys
	 */
	public static void deleteGenSSHKeys() {
		deletePvtGenSSHKey();
		//delete public key
		try {
			File file = new File(PUB_KEY);
			FileUtils.forceDelete(file);
		} catch (Exception ex) {
		}
	}


	/**
	 * delete SSH keys
	 */
	public static void deletePvtGenSSHKey() {
		//delete private key
		try {
			File file = new File(PVT_KEY);
			FileUtils.forceDelete(file);
		} catch (Exception ex) {
		}
	}

	/**
	 * generates system's public/private key par and returns passphrase
	 *
	 * @return passphrase for system generated key
	 */
	public static String keyGen(String passphrase) {
		deleteGenSSHKeys();
		if (StringUtils.isEmpty(AppConfig.getProperty("privateKey")) || StringUtils.isEmpty(AppConfig.getProperty("publicKey"))) {

			//set key type
			int type = KEY_TYPE.equals("rsa") ? KeyPair.RSA : KeyPair.DSA;
			String comment = "keybox@global_key";

			JSch jsch = new JSch();
			try {
				KeyPair keyPair = KeyPair.genKeyPair(jsch, type, KEY_LENGTH);
				keyPair.writePrivateKey(PVT_KEY, passphrase.getBytes());
				keyPair.writePublicKey(PUB_KEY, comment);
                System.out.println("Finger print: " + keyPair.getFingerPrint());
				keyPair.dispose();
			} catch (Exception e) {
				log.error(e.toString(), e);
			}
		}
		return passphrase;
	}

	/**
	 * distributes authorized keys for host system
	 *
	 * @param hostSystem      object contains host system information
	 * @param passphrase      ssh key passphrase
	 * @param password        password to host system if needed
	 * @param newAppKey       generated a new KeyPair
	 * @return status of key distribution
	 */
	public static HostSystem authAndAddPubKey(HostSystem hostSystem, String passphrase, String password, boolean newAppKey) {
		JSch jsch = new JSch();
		Session session = null;
		hostSystem.setStatusCd(HostSystem.SUCCESS_STATUS);
		try {
			
			ApplicationKey appKey = hostSystem.getApplicationKey();
			//check to see if passphrase has been provided
			if (passphrase == null || passphrase.trim().equals("")) {
				passphrase = appKey.getPassphrase();
				//check for null inorder to use key without passphrase
				if (passphrase == null) {
					passphrase = "";
				}
			}
			//add private key
			jsch.addIdentity(appKey.getId().toString(), appKey.getPrivateKey().trim().getBytes(), appKey.getPublicKey().getBytes(), passphrase.getBytes());

			//create session
			session = jsch.getSession(hostSystem.getUser(), hostSystem.getHost(), hostSystem.getPort());

			//set password if passed in
			if (password != null && !password.equals("")) {
				session.setPassword(password);
			}
			java.util.Properties config = new java.util.Properties();
			config.put("StrictHostKeyChecking", "no");
			session.setConfig(config);
			session.connect(SESSION_TIMEOUT);
			ApplicationKey genAppKey = null;
			//Generate new Key?
			if(newAppKey)
			{
				genAppKey = keyGenIntern();
			}
			addPubKey(hostSystem, session, genAppKey);
		} catch (Exception e) {
			hostSystem.setErrorMsg(e.getMessage());
			if (e.getMessage().toLowerCase().contains("userauth fail")) {
				hostSystem.setStatusCd(HostSystem.PUBLIC_KEY_FAIL_STATUS);
			} else if (e.getMessage().toLowerCase().contains("auth fail") || e.getMessage().toLowerCase().contains("auth cancel")) {
				hostSystem.setStatusCd(HostSystem.AUTH_FAIL_STATUS);
			} else if (e.getMessage().toLowerCase().contains("unknownhostexception")){
				hostSystem.setErrorMsg("DNS Lookup Failed");
				hostSystem.setStatusCd(HostSystem.HOST_FAIL_STATUS);
			} else {
				hostSystem.setStatusCd(HostSystem.GENERIC_FAIL_STATUS);
			}
		}

		if (session != null) {
			session.disconnect();
		}
		return hostSystem;
	}


	/**
	 * distributes uploaded item to system defined
	 *
	 * @param hostSystem  object contains host system information
	 * @param session     an established SSH session
	 * @param source      source file
	 * @param destination destination file
	 * @return status uploaded file
	 */
	public static HostSystem pushUpload(HostSystem hostSystem, Session session, String source, String destination) {
		hostSystem.setStatusCd(HostSystem.SUCCESS_STATUS);
		Channel channel = null;
		ChannelSftp c = null;

		try {
			channel = session.openChannel("sftp");
			channel.setInputStream(System.in);
			channel.setOutputStream(System.out);
			channel.connect(CHANNEL_TIMEOUT);

			c = (ChannelSftp) channel;
			destination = destination.replaceAll("~\\/|~", "");

			//get file input stream
			FileInputStream file = new FileInputStream(source);
			c.put(file, destination);
			file.close();

		} catch (Exception e) {
			hostSystem.setErrorMsg(e.getMessage());
			hostSystem.setStatusCd(HostSystem.GENERIC_FAIL_STATUS);
		}
		//exit
		if (c != null) {
			c.exit();
		}
		//disconnect
		if (channel != null) {
			channel.disconnect();
		}
		return hostSystem;
	}


	/**
	 * distributes authorized keys for host system and change application public key
	 *
	 * @param hostSystem      object contains host system information
	 * @param session         an established SSH session
	 * @param genAppKey new application public key value (if null, no Change of application public key)
	 * @return status of key distribution
	 */
	public static HostSystem addPubKey(HostSystem hostSystem, Session session, ApplicationKey genAppKey) {
		Channel channel = null;
		ChannelSftp c = null;
		try {
			channel = session.openChannel("sftp");
			channel.setInputStream(System.in);
			channel.setOutputStream(System.out);
			channel.connect(CHANNEL_TIMEOUT);

			c = (ChannelSftp) channel;

			//return public key list into a input stream
			String authorizedKeys = hostSystem.getAuthorizedKeys().replaceAll("~\\/|~", "");

			String appPubKey = hostSystem.getApplicationKey().getPublicKey().replace("\n", "").trim();

			String keyValue = "";
			//if no overwrite then append to previous auth keys file
			if (keyManagementEnabled) {
				//get keys assigned to system
				List<String> assignedKeys = PublicKeyDB.getPublicKeysForSystem(hostSystem.getId());
				for (String existingKey : assignedKeys) {
					keyValue = keyValue + existingKey.replace("\n", "").trim() + "\n";
				}
			} else {
				try {
					InputStream is = c.get(authorizedKeys);
					BufferedReader reader = new BufferedReader(new InputStreamReader(is));
					String existingKey;
					while ((existingKey = reader.readLine()) != null) {
						existingKey = existingKey.replace("\n", "").trim();
						if (!appPubKey.equals(existingKey)) {
							keyValue = keyValue + existingKey + "\n";
						}
					}
					is.close();
					reader.close();
				} catch (SftpException ex) {
					//ignore exception if file doesn't exist
				}
			}
			
			//New SystemKey set?
			if(genAppKey != null)
			{
				appPubKey = genAppKey.getPublicKey().replace("\n", "").trim();
			}
			keyValue = keyValue + appPubKey + "\n";

			//Save authorizedKeys 
			try {
				c.rename(authorizedKeys, SAVEFILE);
			} catch (SftpException ex) {
				//ignore exception if file doesn't exist
			}
			
			//Write new authorizedKey
			InputStream inputStreamAuthKeyVal = new ByteArrayInputStream(keyValue.getBytes());
			c.put(inputStreamAuthKeyVal, authorizedKeys);
			c.chmod(Integer.parseInt("600",8), authorizedKeys);
			
			//Test Connection
			if(testConnection(hostSystem, genAppKey)){
				if(genAppKey !=null)
				{
					hostSystem.setApplicationKey(genAppKey);
				}
			}else{
				try {
					c.rename(SAVEFILE,authorizedKeys);
				} catch (SftpException ex) {
					//ignore exception if file doesn't exist
				}
			}
			
		} catch (Exception e) {
			hostSystem.setErrorMsg(e.getMessage());
			hostSystem.setStatusCd(HostSystem.GENERIC_FAIL_STATUS);
		}
		//exit
		if (c != null) {
			c.exit();
		}
		//disconnect
		if (channel != null) {
			channel.disconnect();
		}
		return hostSystem;
	}

	/**
	 * Test Connection to System
	 * 
	 * @param hostSystem System to Connection Test
	 * @param genAppKey New generated ApplicationKey from System;
	 * 							if <strong> null </strong>, test with ApplicationKey from System 
	 * @return <strong>TRUE</strong> Test OK <br>
	 * 			<strong>FALSE</strong> Test not OK
	 */
	private static boolean testConnection(HostSystem hostSystem, ApplicationKey genAppKey) {
		JSch jsch = new JSch();
		Session session = null;
		Channel channel = null;
		ChannelSftp c = null;
		boolean testio = true;
		
		try{
			ApplicationKey appKey = hostSystem.getApplicationKey();
			if(genAppKey != null)
			{
				appKey = genAppKey;
			}
			String passphrase = appKey.getPassphrase();
			//check for null inorder to use key without passphrase
			if (passphrase == null) {
				passphrase = "";
			}
			//add private key
			jsch.addIdentity(appKey.getId().toString()+"test", appKey.getPrivateKey().trim().getBytes(), appKey.getPublicKey().getBytes(), passphrase.getBytes());
			
			//create session
			session = jsch.getSession(hostSystem.getUser(), hostSystem.getHost(), hostSystem.getPort());

			java.util.Properties config = new java.util.Properties();
			config.put("StrictHostKeyChecking", "no");
			session.setConfig(config);
			
			session.connect(SESSION_TIMEOUT);
			
			channel = session.openChannel("sftp");
			channel.setInputStream(System.in);
			channel.setOutputStream(System.out);
			channel.connect(CHANNEL_TIMEOUT);
			
			c = (ChannelSftp) channel;
			
			try {
				c.rm(SAVEFILE);
			} catch (SftpException ex) {
				//ignore exception if file doesn't exist
			}
		} catch(Exception e){
			e.printStackTrace();
			testio = false;
		}
		
		//exit
		if (c != null) {
			c.exit();
		}
		//disconnect
		if (channel != null) {
			channel.disconnect();
		}
		
		if (session != null) {
			session.disconnect();
		}
		return testio;
	}


	/**
	 * return the next instance id based on ids defined in the session map
	 *
	 * @param sessionId      session id
	 * @param userSessionMap user session map
	 * @return
	 */
	private static int getNextInstanceId(Long sessionId, Map<Long, UserSchSessions> userSessionMap ){
		Integer instanceId=1;
		if(userSessionMap.get(sessionId)!=null){
			for(Integer id :userSessionMap.get(sessionId).getSchSessionMap().keySet()) {
				if (!id.equals(instanceId) ) {
					if(userSessionMap.get(sessionId).getSchSessionMap().get(instanceId) == null) {
						return instanceId;
					}
				}
				instanceId = instanceId + 1;
			}
		}
		return instanceId;
	}
	
	
	/**
	 * open new ssh session on host system
	 *
	 * @param passphrase     key passphrase for instance
	 * @param password       password for instance
	 * @param userId         user id
	 * @param sessionId      session id
	 * @param hostSystem     host system
	 * @param userSessionMap user session map
	 * @return status of systems
	 */
	public static HostSystem openSSHTermOnSystem(String passphrase, String password, Long userId, Long sessionId, HostSystem hostSystem, Map<Long, UserSchSessions> userSessionMap) {
		JSch jsch = new JSch();
		int instanceId = getNextInstanceId(sessionId,userSessionMap);
		hostSystem.setStatusCd(HostSystem.SUCCESS_STATUS);
		hostSystem.setInstanceId(instanceId);

		SchSession schSession = null;
		try {
			ApplicationKey appKey = hostSystem.getApplicationKey();
			if(appKey == null){
				hostSystem.setErrorMsg("System Key disabled");
				hostSystem.setStatusCd(HostSystem.PRIVAT_KEY_FAIL_STATUS);
				SystemStatusDB.updateSystemStatus(hostSystem, userId);
				SystemDB.updateSystem(hostSystem);
				return hostSystem;
			}

			//check to see if passphrase has been provided
			if (passphrase == null || passphrase.trim().equals("")) {
				passphrase = appKey.getPassphrase();
				//check for null inorder to use key without passphrase
				if (passphrase == null) {
					passphrase = "";
				}
			}
			//add private key
			jsch.addIdentity(appKey.getId().toString(), appKey.getPrivateKey().trim().getBytes(), appKey.getPublicKey().getBytes(), passphrase.getBytes());

			//create session
			Session session = jsch.getSession(hostSystem.getUser(), hostSystem.getHost(), hostSystem.getPort());

			//set password if it exists
			if (password != null && !password.trim().equals("")) {
				session.setPassword(password);
			}
			session.setConfig("StrictHostKeyChecking", "no");
			session.connect(SESSION_TIMEOUT);
			Channel channel = session.openChannel("shell");
			if ("true".equals(AppConfig.getProperty("agentForwarding"))) {
				((ChannelShell) channel).setAgentForwarding(true);
			}
			((ChannelShell) channel).setPtyType("xterm");

			InputStream outFromChannel = channel.getInputStream();

			//new session output
			SessionOutput sessionOutput = new SessionOutput(sessionId, hostSystem);

			Runnable run = new SecureShellTask(sessionOutput, outFromChannel);
			Thread thread = new Thread(run);
			thread.start();

			OutputStream inputToChannel = channel.getOutputStream();
			PrintStream commander = new PrintStream(inputToChannel, true);

			channel.connect();

			schSession = new SchSession();
			schSession.setUserId(userId);
			schSession.setSession(session);
			schSession.setChannel(channel);
			schSession.setCommander(commander);
			schSession.setInputToChannel(inputToChannel);
			schSession.setOutFromChannel(outFromChannel);
			schSession.setHostSystem(hostSystem);

			ApplicationKey genAppKey = null;
			
			if(dynamicKeys && hostSystem.getApplicationKey().isInitialkey() && hostSystem.getInstance().equals("---"))
			{
				genAppKey = keyGenIntern();
			}
			
			//refresh keys for session
			addPubKey(hostSystem, session, genAppKey);

		} catch (Exception e) {
			hostSystem.setErrorMsg(e.getMessage());
			if (e.getMessage().toLowerCase().contains("userauth fail")) {
				hostSystem.setStatusCd(HostSystem.PUBLIC_KEY_FAIL_STATUS);
			} else if (e.getMessage().toLowerCase().contains("auth fail") || e.getMessage().toLowerCase().contains("auth cancel")) {
				hostSystem.setStatusCd(HostSystem.AUTH_FAIL_STATUS);
			} else if (e.getMessage().toLowerCase().contains("unknownhostexception")){
				hostSystem.setErrorMsg("DNS Lookup Failed");
				hostSystem.setStatusCd(HostSystem.HOST_FAIL_STATUS);	
			} else {
				hostSystem.setStatusCd(HostSystem.GENERIC_FAIL_STATUS);
			}
		}

		//add session to map
		if (hostSystem.getStatusCd().equals(HostSystem.SUCCESS_STATUS)) {
			//get the server maps for user
			UserSchSessions userSchSessions = userSessionMap.get(sessionId);

			//if no user session create a new one
			if (userSchSessions == null) {
				userSchSessions = new UserSchSessions();
			}
			Map<Integer, SchSession> schSessionMap = userSchSessions.getSchSessionMap();

			//add server information
			schSessionMap.put(instanceId, schSession);
			userSchSessions.setSchSessionMap(schSessionMap);
			//add back to map
			userSessionMap.put(sessionId, userSchSessions);
		}

		SystemStatusDB.updateSystemStatus(hostSystem, userId);
		SystemDB.updateSystem(hostSystem);
		return hostSystem;
	}


	/**
	 * distributes public keys to all systems
	 */
	public static void distributePubKeysToAllSystems() {

		if (keyManagementEnabled) {
			List<HostSystem> hostSystemList = SystemDB.getAllSystems();
			for (HostSystem hostSystem : hostSystemList) {
				hostSystem = SSHUtil.authAndAddPubKey(hostSystem, null, null, false);
				SystemDB.updateSystem(hostSystem);
			}
		}
	}


	/**
	 * distributes public keys to all systems under profile
	 *
	 * @param profileId profile id
	 */
	public static void distributePubKeysToProfile(Long profileId) {

		if (keyManagementEnabled) {
			List<HostSystem> hostSystemList = ProfileSystemsDB.getSystemsByProfile(profileId);
			for (HostSystem hostSystem : hostSystemList) {
				hostSystem = SSHUtil.authAndAddPubKey(hostSystem, null, null, false);
				SystemDB.updateSystem(hostSystem);
			}
		}
	}

	/**
	 * distributes public keys to all systems under all user profiles
	 *
	 * @param userId user id
	 */
	public static void distributePubKeysToUser(Long userId) {

		if (keyManagementEnabled) {
			for (Profile profile : UserProfileDB.getProfilesByUser(userId)) {
				List<HostSystem> hostSystemList = ProfileSystemsDB.getSystemsByProfile(profile.getId());
				for (HostSystem hostSystem : hostSystemList) {
					hostSystem = SSHUtil.authAndAddPubKey(hostSystem, null, null, false);
					SystemDB.updateSystem(hostSystem);
				}
			}
		}
	}


	/**
	 * returns public key fingerprint
	 *
	 * @param publicKey public key 
	 * @return fingerprint of public key                     
	 */
	public static String getFingerprint(String publicKey){
		String fingerprint=null;
		if(StringUtils.isNotEmpty(publicKey)){
			try {
 				KeyPair keyPair = KeyPair.load(new JSch(), null, publicKey.getBytes());
				if(keyPair != null){
					fingerprint=keyPair.getFingerPrint();
				}
			} catch (JSchException ex){
				log.error(ex.toString(), ex);
			}
		}
		return fingerprint;
	}

	/**
	 * returns public key type 
	 *
	 * @param publicKey public key 
	 * @return fingerprint of public key                     
	 */
	public static String getKeyType(String publicKey){
		String keyType=null;
		if(StringUtils.isNotEmpty(publicKey)){
			try {
				KeyPair keyPair = KeyPair.load(new JSch(), null, publicKey.getBytes());
				if(keyPair != null) {
					int type =keyPair.getKeyType();
					if(KeyPair.DSA == type){
						keyType="DSA";
					} else if (KeyPair.RSA == type){
						keyType="RSA";
					} else if(KeyPair.UNKNOWN ==type){
						keyType="UNKNOWN";
					} else if(KeyPair.ERROR == type){
						keyType="ERROR";
					}
				}
			} catch (JSchException ex){
				log.error(ex.toString(), ex);
			}
		}
		return keyType;
	}

	/**
	 * Generate ApplicationKey for dynamic Keys
	 * 
	 * @return Generated ApplicationKey
	 */
	private static ApplicationKey keyGenIntern() {
		int type = KEY_TYPE.equals("rsa") ? KeyPair.RSA : KeyPair.DSA;
		JSch jsch = new JSch();
		ApplicationKey applicationKey = new ApplicationKey();
		KeyPair keyPair = null;
		try{
			do {
				keyPair = KeyPair.genKeyPair(jsch, type, KEY_LENGTH);
				keyPair.setPublicKeyComment(KEY_COMMENT + Calendar.getInstance().getTime().toString());
			} while (FingerprintDB.isFingerprintExistsInRegion(keyPair.getFingerPrint(),applicationKey.getEc2Region()));

			applicationKey.setKeyname(Long.toString((new Date()).getTime()));
			
			applicationKey.setInitialkey(false);
			applicationKey.setFingerprint(new Fingerprint(keyPair.getFingerPrint()));
			
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			applicationKey.setPassphrase(null);
			keyPair.writePrivateKey(out);
			
			String privateKey = out.toString();
			applicationKey.setPrivateKey(privateKey);
			
			out = new ByteArrayOutputStream();
			keyPair.writePublicKey(out, keyPair.getPublicKeyComment());
			String publicKey = out.toString();
			applicationKey.setPublicKey(publicKey);
			
			applicationKey.setType(SSHUtil.getKeyType(publicKey));
			applicationKey.setUserId(null);
			applicationKey.setEnabled(true);
			PrivateKeyDB.insertApplicationKey(applicationKey);
			applicationKey = PrivateKeyDB.getApplicationKeyByFingerprint(applicationKey.getFingerprint().getFingerprint());
			
		} catch (Exception e) {
			e.printStackTrace();
		}
		return applicationKey;
	}

	/**
	 * Refresh Systems there ApplicationKey older then X days and set new ApplecationKeys
	 * 
	 * @param days Older Then X days
	 */
	public static void refreshApplicationKey(Integer days) {
		List<HostSystem> systemList = SystemDB.getAllSystemsWhereApplicationKeyOlderThan(days);
		
		for (HostSystem hostSystem : systemList) {
			if(hostSystem.getInstance().equals("---")){
				hostSystem = SSHUtil.authAndAddPubKey(hostSystem, null, null, true);
				SystemDB.updateSystem(hostSystem);
			}
		}
	}
}
