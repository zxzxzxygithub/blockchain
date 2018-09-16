package handler;


import org.apache.commons.io.IOUtils;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.hyperledger.fabric.sdk.*;
import org.hyperledger.fabric.sdk.NetworkConfig.OrgInfo;
import org.hyperledger.fabric.sdk.NetworkConfig.UserInfo;
import org.hyperledger.fabric.sdk.exception.InvalidArgumentException;
import org.hyperledger.fabric.sdk.exception.NetworkConfigurationException;
import org.hyperledger.fabric.sdk.security.CryptoSuite;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.security.PrivateKey;
import java.util.*;
import java.util.concurrent.TimeUnit;

import javax.json.Json;
import javax.json.JsonObject;

import static java.lang.String.format;
import static java.nio.charset.StandardCharsets.UTF_8;

public class FabricHelper {
    private Logger logger = LoggerFactory.getLogger(FabricHelper.class);

	private String ymlFileName = "./java-sdk-demo-sdk-config.yaml";
	private String channelName = "channeldemo";
	private String chaincodeName = "chaincode";
	private String accessKey = "6a21c8a2f20a1d4185e817b79647db3eaf03f962";
    private Map<String, HFClient> clientMap;
    private Map<String, Channel> channelMap;
    private FabricHelper() {
        channelMap = new HashMap<>();
        clientMap = new HashMap<>();
    }

    private static class Holder {
        private static FabricHelper instance = new FabricHelper();
    }

    public static FabricHelper getInstance(){
        return Holder.instance;
    }
    
    public FabricHelper setConfigPath(String configPath){
    	this.ymlFileName=configPath;
    	return this;
    }
    
    public FabricHelper setChaincodeName(String chaincodeName) {
    	this.chaincodeName=chaincodeName;
    	return this;
    }
    	
    public FabricHelper setChannelName(String channelName) {
    	this.channelName=channelName;
    	return this;
    }
    
    public FabricHelper setAccessKey(String accesskey) {
    	this.accessKey=accesskey;
    	return this;
    }

    private NetworkConfig loadfromYamlFile(String fileName) {
        try {
            return NetworkConfig.fromYamlFile(new File(fileName));
            
        } catch (Exception e) {
            String msg = "can't load yaml file: " + fileName;
            logger.error(msg, e);
            return null;
        }
    }

    private Channel buildChannel(String channelName, NetworkConfig networkConfig, HFClient client) {
        try {
            client.setCryptoSuite(CryptoSuite.Factory.getCryptoSuite());
            FabricUser user = genFabricUser(accessKey);
            client.setUserContext(user);
            Channel channel = client.loadChannelFromConfig(channelName, networkConfig);
            channel.initialize();
            return channel;
        } catch (Exception e) {
            String msg = "can't construct channel: " + networkConfig.getClientOrganization();
            logger.error(msg, e);
            return null;
        }
    }

    private HFClient getClient(String orgName) {
        HFClient client = clientMap.get(orgName);
        if (client == null) {
            synchronized (clientMap) {
            	client = clientMap.get(orgName);
            	if (client != null) {
            		return client;
            	}
            	
                client = HFClient.createNewInstance();
                try {
                    client.setCryptoSuite(CryptoSuite.Factory.getCryptoSuite());
                    clientMap.put(orgName, client);
                } catch (Exception e) {
                    String msg = "can't construct client: " + orgName;
                    logger.error(msg, e);
                    System.out.println(msg);
                    return null;
                }
            }
        }

        return client;
    }
    
    private Channel getChannel(String accessKey, HFClient client) {
        Channel channel = channelMap.get(accessKey);
        if (channel == null) {
            synchronized (channelMap) {
            	channel = channelMap.get(accessKey);
            	if (channel != null) {
            	   return channel;
            	}
            	
            	NetworkConfig networkConfig = loadfromYamlFile(ymlFileName);
                if (networkConfig == null) {
                    return null;
                }

                try {
                	  
                    networkConfig.getOrdererNames().forEach(item -> {
                        try {
                        	Properties p = networkConfig.getOrdererProperties(item);
                            p.setProperty("clientCertFile", GetTlsCert(ymlFileName, "ordererorg"));
                            p.setProperty("clientKeyFile",  GetTlsKey(ymlFileName, "ordererorg"));
                            p.setProperty("hostnameOverride", item);
                            networkConfig.setOrdererProperties(item, p);
                        } catch (InvalidArgumentException e) {
                            throw new RuntimeException(e);
                        }
                    });

                    networkConfig.getPeerNames().forEach(item -> {
                        try {
                        	Properties p = networkConfig.getPeerProperties(item);
                        	String orgId = getOrgIdByPeer(networkConfig,item);
                            p.setProperty("clientCertFile", GetTlsCert(ymlFileName, orgId));
                            p.setProperty("clientKeyFile",  GetTlsKey(ymlFileName, orgId));
                            p.setProperty("hostnameOverride", item);
                            networkConfig.setPeerProperties(item, p);
                        } catch (InvalidArgumentException e) {
                            throw new RuntimeException(e);
                        }
                    });

                    networkConfig.getEventHubNames().forEach(item -> {
                        try {
                        	Properties p = networkConfig.getEventHubsProperties(item);
                        	String orgId = getOrgIdByPeer(networkConfig,item);
                            p.setProperty("clientCertFile", GetTlsCert(ymlFileName, orgId));
                            p.setProperty("clientKeyFile",  GetTlsKey(ymlFileName, orgId));
                            p.setProperty("hostnameOverride", item);
                            networkConfig.setEventHubProperties(item, p);
                        } catch (InvalidArgumentException e) {
                            throw new RuntimeException(e);
                        }
                    });

                    networkConfig.getChannelNames().forEach( item -> {
                    		if (channelName!="" ){
                    				channelName = item;
                    		}
                    });
					
                } catch (Exception e) {
                    String msg = "can't get channel: " + accessKey;
                    logger.error(msg, e);
                    return null;
                }

                channel = buildChannel(channelName, networkConfig, client);
                if (channel != null) {
                    channelMap.put(accessKey, channel);
                }
            }
        }

        return channel;
    }

    private String getCryptoPath(String configFile, String orgId) {
		JsonObject root = null;
		try {
			InputStream stream = new FileInputStream(configFile);
			Yaml yaml = new Yaml();
			Map<String, Object> map = yaml.load(stream);
			root = Json.createObjectBuilder(map).build();
		} catch (FileNotFoundException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
		JsonObject orgs = root.getJsonObject("organizations");
		for (Map.Entry o : orgs.entrySet()) {
			if (orgId.equals(o.getKey())) {
				JsonObject v = (JsonObject) o.getValue();
				return v.getString("cryptoPath");
			}
		}
		return "";
	}
	
    private String GetTlsCert(String configFile, String orgId){
		String msp = getCryptoPath(configFile, orgId);
		int index = msp.lastIndexOf("msp");
		String ret = msp.substring(0, index)+"tls/server.crt";
		logger.debug("tls cert for " + orgId +",path:"+ ret);
		return ret;
	}
	
	private String GetTlsKey(String configFile, String orgId){
		String msp = getCryptoPath(configFile, orgId);
		int index = msp.lastIndexOf("msp");
		String ret = msp.substring(0, index)+"tls/server.key";
		logger.debug("tls key for " + orgId +",path:"+ ret);
		return ret;
	}
	
	private String getOrgIdByPeer(NetworkConfig config, String peerName){
		for ( OrgInfo o : config.getOrganizationInfos()) {
			for (String p : o.getPeerNames()){
				if ( p.equals(peerName) ){
					return o.getName();
				}
			}
		}
		return "";
	}
	
	private FabricUser genFabricUser(String accessKey) {
		FabricUser user = new FabricUser(accessKey); 
		String msp = getCryptoPath(this.ymlFileName, accessKey);
		String adminPrivateKeyString = extractPemString(msp,"keystore");
		String signedCert = extractPemString(msp, "signcerts");
		
		PrivateKey privateKey = null;
        try {
            privateKey = getPrivateKeyFromString(adminPrivateKeyString);
        } catch (IOException ioe) {
            ioe.printStackTrace();
        }
        
        final PrivateKey privateKeyFinal = privateKey;
		user.setEnrollment(new Enrollment() {
            @Override
            public PrivateKey getKey() {
                return privateKeyFinal;
            }

            @Override
            public String getCert() {
                return signedCert;
            }
        });
		return user;
	}
	
	private String extractPemString(String path, String sub){
		    String pemString = ""; 	
            File dir = new File(path + "/" + sub ); 
            System.out.print(path + "/" + sub);
            for (File f : dir.listFiles()){
				try {
					FileInputStream  stream = new FileInputStream(f);
					pemString = IOUtils.toString(stream, "UTF-8");
	            	return pemString;
				} catch (Exception e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
            }
        return pemString;
    }
	private static PrivateKey getPrivateKeyFromString(String data)
            throws IOException {
        final Reader pemReader = new StringReader(data);
        final PrivateKeyInfo pemPair;
        try (PEMParser pemParser = new PEMParser(pemReader)) {
            pemPair = (PrivateKeyInfo) pemParser.readObject();
        }
        return new JcaPEMKeyConverter().getPrivateKey(pemPair);
    }
	
	
    public boolean invokeBlockchain(String method, String[] args) {
        HFClient client = getClient(accessKey);
        if (client == null) {
        	return false;
        }

        Channel channel = getChannel(accessKey, client);
        if (channel == null) {
            return false;
        }

        Collection<ProposalResponse> successful = new LinkedList<>();
        Collection<ProposalResponse> failed = new LinkedList<>();

        try {
            TransactionProposalRequest req = client.newTransactionProposalRequest();
            ChaincodeID cid = ChaincodeID.newBuilder().setName(chaincodeName).build();
            req.setChaincodeID(cid);
            req.setFcn(method);
            req.setArgs(args);

            Map<String, byte[]> tm2 = new HashMap<>();
            tm2.put("HyperLedgerFabric", "TransactionProposalRequest:JavaSDK".getBytes(UTF_8));
            tm2.put("method", "TransactionProposalRequest".getBytes(UTF_8));
            tm2.put("result", ":)".getBytes(UTF_8));  /// This should be returned see chaincode.
            req.setTransientMap(tm2);

            Collection<ProposalResponse> resps = channel.sendTransactionProposal(req);

            for (ProposalResponse response : resps) {
                if (response.getStatus() == ProposalResponse.Status.SUCCESS) {
                    successful.add(response);
                } else {
                    failed.add(response);
                }
            }

            // Check that all the proposals are consistent with each other. We should have only one set
            // where all the proposals above are consistent.
            Collection<Set<ProposalResponse>> proposalConsistencySets = SDKUtils.getProposalConsistencySets(resps);
            if (proposalConsistencySets.size() != 1) {
                logger.error("Expected only one set of consistent proposal responses but got {}: {}" + proposalConsistencySets.size(), args);
                return false;
            }

            if (failed.size() > 0) {
                ProposalResponse firstTransactionProposalResponse = failed.iterator().next();
                logger.error("Not enough endorsers for {}: {}. endorser error: {}, Was verified: {}",  args, failed.size(),
                        firstTransactionProposalResponse.getMessage(), firstTransactionProposalResponse.isVerified());
                return false;
            }

            BlockEvent.TransactionEvent transactionEvent = channel.sendTransaction(successful).get(30, TimeUnit.SECONDS);
            if (transactionEvent.isValid()) {
                logger.info("Finished transaction with transaction id {}: {}", transactionEvent.getTransactionID(), args);
                return true;
            } else {
                logger.error("can't commit result: {}", args);
                return false;
            }
        } catch (Exception e) {
            String msg = "can't put record to blockchain: " + args;
            logger.error(msg, e);
            return false;
        }
    }

    public String queryBlockchain(String method, String[] params) {
        HFClient client = getClient(accessKey);
        if (client == null) {
            return "{1}";
        }

        Channel channel = getChannel(accessKey, client);
        if (channel == null)  {
            return "{2}";
        }

        try {
            
            QueryByChaincodeRequest queryByChaincodeRequest = client.newQueryProposalRequest();
            queryByChaincodeRequest.setArgs(params);
            queryByChaincodeRequest.setFcn(method);
            queryByChaincodeRequest.setChaincodeID(ChaincodeID.newBuilder().setName(chaincodeName).build());

            Map<String, byte[]> tm2 = new HashMap<>();
            tm2.put("HyperLedgerFabric", "QueryByChaincodeRequest:JavaSDK".getBytes(UTF_8));
            tm2.put("method", "QueryByChaincodeRequest".getBytes(UTF_8));
            queryByChaincodeRequest.setTransientMap(tm2);

            String payload = null;
            Collection<ProposalResponse> queryProposals = channel.queryByChaincode(queryByChaincodeRequest, channel.getPeers());
            for (ProposalResponse proposalResponse : queryProposals) {
                if (!proposalResponse.isVerified() || proposalResponse.getStatus() != ProposalResponse.Status.SUCCESS) {
                    logger.error("Failed query proposal from peer " + proposalResponse.getPeer().getName() + " status: " + proposalResponse.getStatus() +
                            ". Messages: " + proposalResponse.getMessage()
                            + ". Was verified : " + proposalResponse.isVerified());
                } else {
                    payload = proposalResponse.getProposalResponse().getResponse().getPayload().toStringUtf8();
                    logger.info("Query payload from peer {} returned {}", proposalResponse.getPeer().getName(), payload);
                    break;
                }
            }

            return !payload.equals("null") ? payload : "{}";
        } catch (Exception e) {
            String msg = "can't query record from blockchain. condition: " +  accessKey + "," + params;
            logger.error(msg);
            return "{3}";
        }
    }
}
