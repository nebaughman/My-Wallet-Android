/*
 * Copyright 2011-2012 the original author or authors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */


package piuk;

import android.util.Base64;
import org.spongycastle.util.encoders.Hex;
import com.google.bitcoin.core.Base58;
import com.google.bitcoin.core.ECKey;
import com.google.bitcoin.core.NetworkParameters;
import com.google.bitcoin.core.Wallet;
import org.apache.commons.lang.ArrayUtils;
import org.json.simple.JSONValue;
import org.json.simple.parser.JSONParser;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.*;


public class MyWallet {
	private static final int AESBlockSize = 4;
	private static final int PBKDF2Iterations = 10;
	public Map<String, Object> root;
	public String temporyPassword;
	public String temporySecondPassword;

	public static final NetworkParameters params = NetworkParameters.prodNet();

	public MyWallet(String base64Payload, String password) throws Exception {
		this.root = decryptPayload(base64Payload, password);

		if (root == null)
			throw new Exception("Error Decrypting Wallet");
	}

	//Create a new Wallet
	public MyWallet() throws Exception {
		this.root = new HashMap<String, Object>();

		root.put("guid", UUID.randomUUID().toString());
		root.put("sharedKey", UUID.randomUUID().toString());

		List<Map<String, Object>> keys = new ArrayList<Map<String, Object>>();
		List<Map<String, Object>> address_book = new ArrayList<Map<String, Object>>();

		root.put("keys", keys);
		root.put("address_book", address_book);

		addKey(new ECKey(), "New");
	}

	@SuppressWarnings("unchecked")
	public List<Map<String, Object>> getKeysMap() {
		return (List<Map<String, Object>>) root.get("keys");
	}

	public String[] getActiveAddresses() {
		List<String> list = new ArrayList<String>();
		for (Map<String, Object> map : getKeysMap()) {
			if (map.get("tag") == null || (Long)map.get("tag") == 0)
				list.add((String) map.get("addr"));
		}
		return list.toArray(new String[list.size()]);
	}

	public String[] getArchivedAddresses() {
		List<String> list = new ArrayList<String>();
		for (Map<String, Object> map : getKeysMap()) {
			if (map.get("tag") != null && (Long)map.get("tag") == 2)
				list.add((String) map.get("addr"));
		}
		return list.toArray(new String[list.size()]);
	}

	@SuppressWarnings("unchecked")
	public List<Map<String, Object>> getAddressBookMap() {
		return (List<Map<String, Object>>) root.get("address_book");
	}


	public boolean isDoubleEncrypted() {
		Object double_encryption = root.get("double_encryption");
		if (double_encryption != null)
			return (Boolean)double_encryption;
		else
			return false;
	}

	public String getGUID() {
		return (String)root.get("guid");
	}

	public String getSharedKey() {
		return (String)root.get("sharedKey");
	}

	public String getDPasswordHash() {
		return (String)root.get("dpasswordhash");
	}

	public void setTemporyPassword(String password) {
		this.temporyPassword = password;
	}

	public void setTemporySecondPassword(String secondPassword) {
		this.temporySecondPassword = secondPassword;
	}

	public String toJSONString() {
		return JSONValue.toJSONString(root);
	}

	public String getPayload() throws Exception {
		return encrypt(toJSONString(), this.temporyPassword);
	}

	public ECKey decodePK(String base58Priv) throws Exception {
		if (this.isDoubleEncrypted()) {

			if (this.temporySecondPassword == null)
				throw new Exception("You must provide a second password");

			base58Priv = decryptPK(base58Priv, getSharedKey(), this.temporySecondPassword);
		}

		byte[] privBytes = Base58.decode(base58Priv);

		//Prppend a zero byte to make the biginteger unsigned
		byte[] appendZeroByte = ArrayUtils.addAll(new byte[1], privBytes);

		ECKey ecKey = new ECKey(new BigInteger(appendZeroByte));

		return ecKey;
	}

	public Map<String, String> getLabelMap() {
		Map<String, String> _labelMap = new HashMap<String, String>();

		List<Map<String, Object>> addressBook = this.getAddressBookMap();

		if (addressBook != null) {
			for (Map<String, Object> addr_book : addressBook) {
				_labelMap.put((String)addr_book.get("addr"), (String)addr_book.get("label"));
			}
		}

		if (this.getKeysMap() != null) {
			for (Map<String, Object> key_map : this.getKeysMap()) {
				String label = (String)key_map.get("label");

				if (label != null)
					_labelMap.put((String)key_map.get("addr"), label);
			}
		}

		return _labelMap;
	}

	public Map<String, Object> findAddressBookEntry(String address) {
		List<Map<String, Object>> addressBook = this.getAddressBookMap();

		if (addressBook != null) {
			for (Map<String, Object> addr_book : addressBook) {
				if (addr_book.get("addr").equals(address))
					return addr_book;
			}
		}

		return null;
	}
	public Map<String, Object> findKey(String address) {
		for (Map<String, Object> key : this.getKeysMap()) {
			String addr = (String) key.get("addr");

			if (addr.equals(address))
				return key;
		}
		return null;
	}

	public boolean isMine(String address) {
		for (Map<String, Object> key : this.getKeysMap()) {
			String addr = (String) key.get("addr");

			if (addr.equals(address))
				return true;
		}

		return false;
	}

	public void setTag(String address, long tag) {
		if (this.isMine(address)) {
			findKey(address).put("tag", tag);
		}
	}

	public void addLabel(String address, String label) {
		if (this.isMine(address)) {
			findKey(address).put("label", label);
		} else {
			Map<String, Object> entry = findAddressBookEntry(address);
			if (entry != null) {
				entry.put("label", label);
			} else {
				List<Map<String, Object>> addressBook = this.getAddressBookMap();

				if (addressBook == null) {
					addressBook = new ArrayList<Map<String, Object>>();
					root.put("address_book", addressBook);
				}

				HashMap<String, Object> map = new HashMap<String, Object>();
				map.put("addr", address);
				map.put("label", label);

				addressBook.add(map);
			}
		}

	}

	protected void addKeysTobitoinJWallet(Wallet wallet) throws Exception {

		wallet.keychain.clear();

		for (Map<String, Object> key : this.getKeysMap()) {

			String base58Priv = (String) key.get("priv");
			String addr = (String) key.get("addr");

			if (base58Priv == null) {
				continue;
			}

			MyECKey encoded_key = new MyECKey(addr, base58Priv, this);

			if (key.get("label") != null)
				encoded_key.setLabel((String) key.get("label"));

			if (key.get("tag") != null) {
				Long tag = (Long) key.get("tag");

				encoded_key.setTag((int)(long)tag);
			}

			wallet.addKey(encoded_key);
		}
	}

	public Wallet getBitcoinJWallet() throws Exception {
		//Construct a BitcoinJ wallet containing all our private keys
		Wallet keywallet = new Wallet(params);

		addKeysTobitoinJWallet(keywallet);

		return keywallet;
	}

	public boolean addKey(ECKey key, String label) throws Exception {
		Map<String, Object> map = new HashMap<String, Object>();

		String base58Priv = new String(Base58.encode(key.getPrivKeyBytes()));

		map.put("addr", key.toAddress(params).toString());

		if (label != null) {
			if (label.length() == 0 || label.length() > 255)
				throw new Exception("Label must be between 0 & 255 characters");

			map.put("label", label);
		}

		if (this.isDoubleEncrypted()) {
			if (temporySecondPassword == null)
				throw new Exception("You must provide a second password");

			map.put("priv", encryptPK(base58Priv, getSharedKey(), temporySecondPassword));

		} else {
			map.put("priv", base58Priv);
		}

		getKeysMap().add(map);

		return true;
	}

	public boolean validateSecondPassword(String secondPassword) {
		try {
			MessageDigest md = MessageDigest.getInstance("SHA-256");

			//N Rounds of SHA256
			byte[] data = md.digest((getSharedKey() + secondPassword).getBytes("UTF-8"));
			for (int ii = 1; ii < PBKDF2Iterations; ++ii) {
				data = md.digest(data);
			}

			String dpasswordhash  = new String(Hex.encode(data));
			if (dpasswordhash.equals(getDPasswordHash()))
				return true;
		} catch (Exception e) {
			e.printStackTrace();
		}

		return false;
	}

	//AES 256 PBKDF2 CBC iso10126 decryption
	//16 byte IV must be prepended to ciphertext - Compatible with crypto-js
	public static String decrypt(String ciphertext, String password) throws Exception {
		byte[] cipherdata = Base64.decode(ciphertext, Base64.NO_WRAP);

		//Sperate the IV and cipher data
		byte[] iv = Arrays.copyOfRange(cipherdata, 0, AESBlockSize * 4);
		byte[] input = Arrays.copyOfRange(cipherdata, AESBlockSize * 4, cipherdata.length);

		IvParameterSpec ivspec = new IvParameterSpec(iv);

		SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1");
		KeySpec spec = new PBEKeySpec(password.toCharArray(), iv, PBKDF2Iterations, 256);
		SecretKey tmp = factory.generateSecret(spec);

		SecretKey secret = new SecretKeySpec(tmp.getEncoded(), "AES");

		byte[] output = null;

		Cipher cipher = Cipher.getInstance("AES/CBC/ISO10126Padding");
		cipher.init(Cipher.DECRYPT_MODE, secret, ivspec);
		output = cipher.doFinal(input);

		return new String(output, "UTF-8");
	}

	//Encrypt compatible with crypto-js
	public static String encrypt(String text, String password) throws Exception{

		if (password == null)
			throw new Exception("You must provide an ecryption password");

		//Use secure random to generate a 16 byte iv
		SecureRandom random = new SecureRandom();
		byte iv[] = new byte[AESBlockSize*4];
		random.nextBytes(iv);

		byte[] textbytes = text.getBytes("UTF-8");

		SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1");
		KeySpec spec = new PBEKeySpec(password.toCharArray(), iv, PBKDF2Iterations, 256);
		SecretKey tmp = factory.generateSecret(spec);

		IvParameterSpec ivspec = new IvParameterSpec(iv);

		SecretKey secret = new SecretKeySpec(tmp.getEncoded(), "AES");
		Cipher cipher = Cipher.getInstance("AES/CBC/ISO10126Padding");
		cipher.init(Cipher.ENCRYPT_MODE, secret, ivspec);

		byte[] output = cipher.doFinal(textbytes);

		//Append to IV to the output
		byte[] ivAppended = ArrayUtils.addAll(iv, output);

		return new String(Base64.encode(ivAppended, Base64.NO_WRAP), "UTF-8");
	}

	//Decrypt a double encrypted private key
	public static String decryptPK(String key, String sharedKey, String password) throws Exception {
		return decrypt(key, sharedKey + password);
	}

	//Decrypt a double encrypted private key
	public static String encryptPK(String key, String sharedKey, String password) throws Exception {
		return encrypt(key, sharedKey + password);
	}

	//Decrypt a Wallet file and parse the JSON
	@SuppressWarnings("unchecked")
	public static Map<String, Object> decryptPayload(String payload, String password) throws Exception {
		if (payload == null || payload.length() == 0 || password == null || password.length() == 0)
			return null;

		String decrypted = decrypt(payload, password);

		if (decrypted == null || decrypted.length() == 0)
			return null;

		JSONParser parser = new JSONParser();

		try {
			return (Map<String, Object>) parser.parse(decrypted);
		} catch (Exception e) {
			System.out.println(decrypted);

			throw e;
		}
	}

}
