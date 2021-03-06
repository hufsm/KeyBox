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
package com.keybox.common.db;

import com.keybox.common.util.AppConfig;
import com.keybox.manage.db.PrivateKeyDB;
import com.keybox.manage.model.ApplicationKey;
import com.keybox.manage.model.Auth;
import com.keybox.manage.model.Fingerprint;
import com.keybox.manage.model.SessionOutput;
import com.keybox.manage.util.DBUtils;
import com.keybox.manage.util.EncryptionUtil;
import com.keybox.manage.util.RefreshApplicationKeyUtil;
import com.keybox.manage.util.RefreshAuthKeyUtil;
import com.keybox.manage.util.SSHUtil;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Initial startup task.  Creates an SQLite DB and generates
 * the system public/private key pair if none exists
 */
@WebServlet(name = "DBInitServlet",
		urlPatterns = {"/config"},
		loadOnStartup = 1)
public class DBInitServlet extends javax.servlet.http.HttpServlet {

    private static Logger log = LoggerFactory.getLogger(DBInitServlet.class);

	/**
	 * task init method that created DB and generated public/private keys
	 *
	 * @param config task config
	 * @throws ServletException
	 */
	public void init(ServletConfig config) throws ServletException {

		super.init(config);

		Connection connection = null;
		Statement statement = null;
		//check if reset ssh application key is set
		boolean resetSSHKey = "true".equals(AppConfig.getProperty("resetApplicationSSHKey"));
		try {
			connection = DBUtils.getConn();
			statement = connection.createStatement();

			ResultSet rs = statement.executeQuery("select * from information_schema.tables where upper(table_name) = 'USERS' and table_schema='PUBLIC'");
			if (rs == null || !rs.next()) {
				resetSSHKey = true;
				statement.executeUpdate("create table if not exists users ("
						+ "id INTEGER PRIMARY KEY AUTO_INCREMENT,"
						+ "first_nm varchar, "
						+ "last_nm varchar, "
						+ "email varchar, "
						+ "username varchar not null, "
						+ "password varchar, "
						+ "auth_token varchar, "
						+ "enabled boolean not null default true, "
						+ "pwreset boolean not null default false, "
						+ "auth_type varchar not null default '" + Auth.AUTH_BASIC+ "', "
						+ "user_type varchar not null default '" + Auth.ADMINISTRATOR + "', "
						+ "salt varchar, "
						+ "otp_secret varchar)");
				
				statement.executeUpdate("create table if not exists user_theme (user_id INTEGER PRIMARY KEY, bg varchar(7), fg varchar(7), d1 varchar(7), d2 varchar(7), d3 varchar(7), d4 varchar(7), d5 varchar(7), d6 varchar(7), d7 varchar(7), d8 varchar(7), b1 varchar(7), b2 varchar(7), b3 varchar(7), b4 varchar(7), b5 varchar(7), b6 varchar(7), b7 varchar(7), b8 varchar(7), foreign key (user_id) references users(id) on delete cascade) ");

				//INFO:EC2Integration
				statement.executeUpdate("create table if not exists aws_credentials ("
						+ "id INTEGER PRIMARY KEY AUTO_INCREMENT, "
						+ "access_key varchar not null, "
						+ "secret_key varchar not null)");
				
				statement.executeUpdate("create table if not exists system ("
						+ "id INTEGER PRIMARY KEY AUTO_INCREMENT, "
						+ "display_nm varchar not null, "
						+ "user varchar not null, "
						+ "host varchar not null, "
						+ "port INTEGER not null, "
						+ "authorized_keys varchar not null, "
						+ "status_cd varchar not null default 'INITIAL', "
						+ "enabled boolean not null default true, "
						+ "instance_id varchar DEFAULT '---', "
						+ "region varchar DEFAULT '---',"
						+ ")");
				
				statement.executeUpdate("create table if not exists profiles ("
						+ "id INTEGER PRIMARY KEY AUTO_INCREMENT, "
						+ "nm varchar not null, "
						+ "tag varchar not null, "
						+ "desc varchar not null)");
				
				statement.executeUpdate("create table if not exists system_map ("
						+ "profile_id INTEGER, "
						+ "system_id INTEGER, "
								+ "foreign key (profile_id) references profiles(id) on delete cascade , "
								+ "foreign key (system_id) references system(id) on delete cascade, "
								+ "primary key (profile_id, system_id))");
				
				statement.executeUpdate("create table if not exists user_map ("
						+ "user_id INTEGER, "
						+ "profile_id INTEGER, "
								+ "foreign key (user_id) references users(id) on delete cascade, "
								+ "foreign key (profile_id) references profiles(id) on delete cascade, "
								+ "primary key (user_id, profile_id))");
				
				statement.executeUpdate("create table if not exists fingerprint (id INTEGER PRIMARY KEY AUTO_INCREMENT, fingerprint varchar)");
				
				statement.executeUpdate("create table if not exists application_key ("
						+ "id INTEGER PRIMARY KEY AUTO_INCREMENT, "
						+ "keyname varchar not null, "
						+ "public_key varchar not null, "
						+ "private_key varchar not null, "
						+ "passphrase varchar,"
						+ "initialKey boolean DEFAULT FALSE, "
						+ "user_id INTEGER DEFAULT null, "
						+ "type varchar not null, "
						+ "fingerprint_id INTEGER, "
						+ "enabled boolean not null default true, "
						+ "create_dt timestamp not null default CURRENT_TIMESTAMP(),"
						+ "ec2_region varchar DEFAULT 'NO_EC2_REGION', "
								+ "foreign key (user_id) references users(id), "
								+ "foreign key (fingerprint_id) references fingerprint(id))");
				
				statement.executeUpdate("create table if not exists application_key_system ("
						+ "system_id INTEGER, "
						+ "application_key_id INTEGER, "
						+ "active boolean, "
								+ "foreign key (system_id) references system(id) ON DELETE CASCADE, "
								+ "foreign key (application_key_id) references application_key(id) ON DELETE CASCADE)");
				

				statement.executeUpdate("create table if not exists status ("
						+ "id INTEGER, "
						+ "user_id INTEGER, "
						+ "status_cd varchar not null default 'INITIAL', "
								+ "foreign key (id) references system(id) on delete cascade, "
								+ "foreign key (user_id) references users(id) on delete cascade, primary key(id, user_id))");
				
				statement.executeUpdate("create table if not exists scripts ("
						+ "id INTEGER PRIMARY KEY AUTO_INCREMENT, "
						+ "user_id INTEGER, "
						+ "display_nm varchar not null, "
						+ "script varchar not null, "
								+ "foreign key (user_id) references users(id) on delete cascade)");

				statement.executeUpdate("create table if not exists public_keys ("
						+ "id INTEGER PRIMARY KEY AUTO_INCREMENT, "
						+ "key_nm varchar not null, "
						+ "type varchar, "
						+ "fingerprint_id INTEGER, "
						+ "public_key varchar, "
						+ "enabled boolean not null default true, "
						+ "create_dt timestamp not null default CURRENT_TIMESTAMP(), "
						+ "user_id INTEGER, "
						+ "profile_id INTEGER, "
								+ "foreign key (profile_id) references profiles(id) on delete cascade, "
								+ "foreign key (user_id) references users(id) on delete cascade, "
								+ "foreign key (fingerprint_id) references fingerprint(id) on delete cascade)");
				
				
				statement.executeUpdate("create table if not exists session_log ("
						+ "id BIGINT PRIMARY KEY AUTO_INCREMENT, "
						+ "user_id INTEGER, "
						+ "session_tm timestamp default CURRENT_TIMESTAMP, "
								+ "foreign key (user_id) references users(id) on delete cascade )");
				
				statement.executeUpdate("create table if not exists terminal_log ("
						+ "session_id BIGINT, "
						+ "instance_id INTEGER, "
						+ "system_id INTEGER, "
						+ "output varchar not null, "
						+ "log_tm timestamp default CURRENT_TIMESTAMP, "
								+ "foreign key (session_id) references session_log(id) on delete cascade, "
								+ "foreign key (system_id) references system(id) on delete cascade)");

				//insert default admin user
				String salt = EncryptionUtil.generateSalt();
				PreparedStatement pStmt = connection.prepareStatement("insert into users (username, password, user_type, salt) values(?,?,?,?)");
				pStmt.setString(1, "admin");
				pStmt.setString(2, EncryptionUtil.hash("changeme" + salt));
				pStmt.setString(3, Auth.MANAGER);
				pStmt.setString(4, salt);
				pStmt.execute();
				DBUtils.closeStmt(pStmt);

			}
			DBUtils.closeRs(rs);

			//if reset ssh application key then generate new key
			if (resetSSHKey) {

				//delete old key entry
				PreparedStatement pStmt = connection.prepareStatement("delete from application_key");
				pStmt.execute();
				DBUtils.closeStmt(pStmt);

				//generate new key and insert passphrase
				System.out.println("Setting KeyBox SSH public/private key pair");

				//generate application pub/pvt key and get values (KeyBoxInitialKey)
				ApplicationKey firstAppKey = new ApplicationKey();
				String passphrase = SSHUtil.keyGen();
				String publicKey = SSHUtil.getPublicKey();
				String privateKey = SSHUtil.getPrivateKey();

				firstAppKey.setFingerprint(new Fingerprint(SSHUtil.getFingerprint(publicKey)));
				firstAppKey.setKeyname("KeyBoxInitialKey");
				firstAppKey.setPublicKey(publicKey);
				firstAppKey.setPrivateKey(privateKey);
				firstAppKey.setPassphrase(passphrase);
				firstAppKey.setInitialkey(true);
				firstAppKey.setType(SSHUtil.getKeyType(publicKey));
				firstAppKey.setEnabled(true);
				firstAppKey.setUserId(null);
				
				//insert new keys
				PrivateKeyDB.insertApplicationKey(firstAppKey);

				System.out.println("KeyBox Generated Global Public Key:");
				System.out.println(publicKey);

				passphrase = null;
				publicKey = null;
				privateKey = null;

				//set config to default
				AppConfig.updateProperty("publicKey", "");
				AppConfig.updateProperty("privateKey", "");
				AppConfig.updateProperty("defaultSSHPassphrase", "${randomPassphrase}");
				
				//set to false
				AppConfig.updateProperty("resetApplicationSSHKey", "false");

			}

			//delete ssh keys
			SSHUtil.deletePvtGenSSHKey();


		} catch (Exception ex) {
            log.error(ex.toString(), ex);
		}

		DBUtils.closeStmt(statement);
		DBUtils.closeConn(connection);

		RefreshAuthKeyUtil.startRefreshAllSystemsTimerTask();
		RefreshApplicationKeyUtil.startRefreshAllSystemsTimerTask();
	}

}
