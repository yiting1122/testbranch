package com.netease.backend.redis.cloud.agent;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;

import com.netease.backend.redis.cloud.agent.handle.HeartBeatHandle;
import com.netease.backend.redis.cloud.agent.handle.SentinelHeartBeatHandle;
import com.netease.backend.redis.cloud.agent.handle.VmMessageHandle;
import com.netease.backend.redis.cloud.agent.result.AgentResultFactory;
import com.netease.backend.redis.cloud.agent.result.BaseResult;
import com.netease.backend.redis.cloud.agent.result.RedisCmdResult;
import com.netease.backend.redis.cloud.config.NasConfig;
import com.netease.backend.redis.cloud.consts.NcrConsts;
import com.netease.backend.redis.cloud.exception.NCRException;
import com.netease.backend.redis.cloud.metadata.AgentHeartBeatInfo;
import com.netease.backend.redis.cloud.metadata.AlarmType;
import com.netease.backend.redis.cloud.metadata.ErrorCode;
import com.netease.backend.redis.cloud.metadata.SentinelHeartBeatInfo;
import com.netease.backend.redis.cloud.service.IAlarmService;
import com.netease.backend.redis.cloud.util.DescriptionUtil;
import com.netease.pomelo.agentservice.core.AckCallback;
import com.netease.pomelo.agentservice.core.DisconnectCallback;
import com.netease.pomelo.agentservice.core.MessageCallback;
import com.netease.pomelo.agentservice.core.PomeloClient;
import com.netease.pomelo.agentservice.core.ResultCallback;
import com.netease.pomelo.agentservice.exception.PomeloClientConnectException;
import com.netease.pomelo.agentservice.exception.PomeloClientInvalidParameterException;
import com.netease.pomelo.agentservice.exception.PomeloClientNameException;
import com.netease.pomelo.agentservice.exception.PomeloClientNotInitException;
import com.netease.pomelo.agentservice.exception.PomeloClientNotRegisterException;

public class NASServer {
	private static final Logger logger = Logger.getLogger(NASServer.class);
	private final int OK = 200;
	private final String CHECK_TAG = "checkResult";
	private final String SENTINEL_CHECKTAG = "sentinelTag";
	private final String TAG = "commonResult";
	private NasConfig nasConfig;
	private final String STATUS_NAME = "status";
	private final String RESULT_NAME = "result";
	private final String SUCCESS_STATUS = "success";
	private final int DEFAULT_SUCCESS_CODE = 0;
	private final int DEFAULT_FAIL_CODE = -1;
	private final String CHECK_TYPE = "check";
	private int reconnectMaxTimes;
	private int reconnectSleepMillis;

	private PomeloClient client;
	private HeartBeatHandle heartBeatHandle;
	private VmMessageHandle vmMessageHandle;
	private SentinelHeartBeatHandle sentinelHeartBeatHandle;
	private ConcurrentMap<String, AgentHeartBeatInfo> checkMap = new ConcurrentHashMap<String, AgentHeartBeatInfo>();
	private ConcurrentMap<String, SentinelHeartBeatInfo> sentinelCheckMap = new ConcurrentHashMap<String, SentinelHeartBeatInfo>();
	private IAlarmService alarmService;

	public void setNasConfig(NasConfig nasConfig) {
		this.nasConfig = nasConfig;
	}

	public void setHeartBeatHandle(HeartBeatHandle heartBeatHandle) {
		this.heartBeatHandle = heartBeatHandle;
	}

	public void init() throws NCRException {
		int rs = 0;
		client = PomeloClient.getInstance();
		client.init(nasConfig.getHost(), nasConfig.getPort());
		try {
			// connect to the agent server
			client.connect();

			registerCallback();

			logger.info("nasServer init product = " + nasConfig.getProduct()
					+ ", serverName = " + nasConfig.getServerName()
					+ ", key = " + nasConfig.getKey() + ", secret = "
					+ nasConfig.getSecret());
			// register serverName
			rs = client.register(nasConfig.getProduct(),
					nasConfig.getServerName(), nasConfig.getKey(),
					nasConfig.getSecret(), null);
			if (rs != OK) {
				logger.error("client register error, rs = " + rs);
				throw new NCRException(ErrorCode.NCR_NAS_INIT_FAILURE, "client register error, rs = " + rs);
			}
		} catch (PomeloClientNotInitException e) {
			logger.error("", e);
			throw new NCRException(ErrorCode.NCR_NAS_INIT_FAILURE, e.getMessage());
		} catch (PomeloClientNameException e) {
			logger.error("", e);
			throw new NCRException(ErrorCode.NCR_NAS_INIT_FAILURE, e.getMessage());
		} catch (PomeloClientInvalidParameterException e) {
			logger.error("", e);
			throw new NCRException(ErrorCode.NCR_NAS_INIT_FAILURE, e.getMessage());
		}catch (PomeloClientConnectException e) {
			logger.error("", e);
			throw new NCRException(ErrorCode.NCR_NAS_INIT_FAILURE, e.getMessage());
		}
	}

	/**
	 * 重连agent
	 */
	public boolean reconnect() {
		int retry = 0;
		boolean connRetry = true;
		while (connRetry && (retry < reconnectMaxTimes)) {
			connRetry = false;
			try {
				Thread.sleep(reconnectSleepMillis);
			} catch (InterruptedException e) {
				logger.error("reconnect sleep is interruted.", e);
			}

			try {
				client.connect();
				break;
			} catch (PomeloClientNotInitException e) {
				logger.error("pomelo client is not init.", e);
				connRetry = true;
			} catch (PomeloClientConnectException e) {
				logger.error("pomelo client is connected.", e);
				connRetry = true;
			} catch (Exception e) {
				logger.error("client.connect exception", e);
				connRetry = true;
			}

			retry++;
		}

		logger.warn("nasServer reconnect retry count:" + retry);

		if (false == connRetry) {
			// Pomelo Client注册Pomelo集群服务器，对返回码HttpCode进行验证
			try {
				logger.warn("nasServer reconnect product = "
						+ nasConfig.getProduct() + ", serverName = "
						+ nasConfig.getServerName() + ", key = "
						+ nasConfig.getKey() + ", secret = "
						+ nasConfig.getSecret());
				// register serverName
				int rs = client.register(nasConfig.getProduct(),
						nasConfig.getServerName(), nasConfig.getKey(),
						nasConfig.getSecret(), null);
				if (rs != OK) {
					logger.error("reconnect client register error, rs = " + rs);

					// alarm
					alarmService.sendAlarm(AlarmType.AgentReconnectError, nasConfig.getProduct(),
							nasConfig.getProduct(), DescriptionUtil.getInstance().getDesc("AgentReconnectRegisterErrorDesc", String.valueOf(rs)));
					
					return false;
				}

				logger.warn("nasServer reconnect done.");
				
				return true;
			} catch (PomeloClientNameException e) {
				logger.error("PomeloClientNameException", e);

				// alarm
				alarmService.sendAlarm(AlarmType.AgentReconnectError, nasConfig.getProduct(),
						nasConfig.getProduct(), "PomeloClientNameException:"+ e.getMessage());
			} catch (PomeloClientInvalidParameterException e) {
				logger.error("PomeloClientInvalidParameterException", e);

				// alarm
				alarmService.sendAlarm(AlarmType.AgentReconnectError, nasConfig.getProduct(),
						nasConfig.getProduct(), "PomeloClientInvalidParameterException:"+ e.getMessage());
			} catch (Exception e) {
				logger.error("Exception", e);
				
				// alarm
				alarmService.sendAlarm(AlarmType.AgentReconnectError, nasConfig.getProduct(),
						nasConfig.getProduct(), "Exception:"+ e.getMessage());
			}
			
			return false;
		} else {
			logger.error("Reconnect Failed, retry timeout!");

			// alarm
			alarmService.sendAlarm(AlarmType.AgentReconnectTimeout, nasConfig.getProduct(),
					nasConfig.getProduct());
			
			return false;
		}
	}

	private void registerCallback() {
		// MessageCallback用来接受公共Agent主动上发的消息，
		// 主要有两种重要的上发消息:公共Agent上线消息和公共Agent下线消息
		client.addCallback(null, new MessageCallback() {

			public void handle(String agentId, int type, JSONObject value) {
				logger.info("message callback:agentId:" + agentId + ",type:"
						+ type + ",value:" + value);
				switch (type) {
				case 0:
					break;
				case 1:
					break;
				case 2:
					break;
				case 3:
					vmMessageHandle.handleUp(agentId);
					break;
				case 4:
					vmMessageHandle.handleDown(agentId);
					break;
				}
			}
		});

		// 处理结果回调，在收到相应命令/消息的结果/响应时调用
		client.addCallback(CHECK_TAG, new ResultCallback() {

			public void handle(String agentId, String messageId,
					JSONObject value) {
				logger.debug("CHECK_TAG result callback:agentId:" + agentId
						+ ",messageId:" + messageId + ",value:" + value);
				AgentHeartBeatInfo info = checkMap.get(agentId);
				if (info == null) {
					logger.error("HeartBeatInfo null:agentId:" + agentId
							+ ",messageId:" + messageId + ",value:" + value);
					return;
				}

				BaseResult result = null;
				try {
					// 忽略这样的消息
					// {"product":"NCR","result":"","fail":[],"tag":"sentinelTag","code":200,"success":["redisCmd/sentinel_check.py"]}
					if (value.optString("result").equals("") == true && value.optInt("code") == 200) {
						logger.info("HeartBeatInfo cycle task registered:" + agentId
								+ ",messageId:" + messageId + ",value:" + value);
						return ;
					}
					
					result = dealResult(agentId ,value, CHECK_TYPE,
							info.getInstanceCount());
				} catch (NCRException e) {
					logger.error("HeartBeatInfo exception:" + agentId
							+ ",messageId:" + messageId + ",value:" + value, e);
					return;
				}

				heartBeatHandle.execute(agentId, messageId, result, info);
			}
		});
		
		// 处理sentinel结果回调，在收到相应命令/消息的结果/响应时调用
		client.addCallback(SENTINEL_CHECKTAG, new ResultCallback() {

			public void handle(String agentId, String messageId,
					JSONObject value) {
				logger.debug("SENTINEL_CHECKTAG result callback:agentId:" + agentId
						+ ",messageId:" + messageId + ",value:" + value);
				SentinelHeartBeatInfo info = sentinelCheckMap.get(agentId);
				if (info == null) {
					logger.error("SentinelHeartBeatInfo null:agentId:" + agentId
							+ ",messageId:" + messageId + ",value:" + value);
					return;
				}

				try {
					// 忽略这样的消息
					// {"product":"NCR","result":"","fail":[],"tag":"sentinelTag","code":200,"success":["redisCmd/sentinel_check.py"]}
					if (value.optString("result").equals("") == true && value.optInt("code") == 200) {
						logger.info("SentinelHeartBeatInfo cycle task registered:" + agentId
								+ ",messageId:" + messageId + ",value:" + value);
						return ;
					}
					
					RedisCmdResult result = dealSentinelResult(agentId, value);
					sentinelHeartBeatHandle.execute(agentId, messageId, info, result);
				} catch (NCRException e) {
					logger.error("HeartBeatInfo exception:" + agentId
							+ ",messageId:" + messageId + ",value:" + value, e);
				}
			}
		});

		// 处理结果回调，在收到相应命令/消息的结果/响应时调用
		client.addCallback(TAG, new ResultCallback() {

			public void handle(String agentId, String messageId,
					JSONObject value) {
				logger.info("TAG result callback:agentId:" + agentId
						+ ",messageId:" + messageId + ",value:" + value);
			}
		});

		client.setDisconnectCallback(new DisconnectCallback() {

			public void onDisconnect() {
				logger.error("agent onDisconnect");
				while (true) {
					boolean result = reconnect();
					logger.info("agent onDisconnect reconnect result:" + result);
					if (true == result) {
						break;
					}
				}
				  
			}
		});
	}

	public String[] getAgents() {
		String[] agents = null;
		try {
			agents = client.getAgents();
		} catch (PomeloClientNotRegisterException e) {
			logger.error("", e);
		} catch (PomeloClientInvalidParameterException e) {
			logger.error("", e);
		}
		
		if (agents == null) {
			// alarm
			alarmService.sendAlarm(AlarmType.AgentExecScriptError,
					nasConfig.getProduct(),
					nasConfig.getProduct(),
					DescriptionUtil.getInstance().getDesc("NASGetAgentsErrorDesc"));	
		}

		return agents;
	}
	
	public JSONArray getAgentsInfo(Map<String, String> parameters) {
		JSONArray agents = null;
		try {
			agents = client.getAgentsInfo(parameters);
		} catch (PomeloClientNotRegisterException e) {
			logger.error("", e);
		} catch (PomeloClientInvalidParameterException e) {
			logger.error("", e);
		}
		
		if (agents == null) {
			// alarm
			alarmService.sendAlarm(AlarmType.AgentExecScriptError,
					nasConfig.getProduct(),
					nasConfig.getProduct(),
					DescriptionUtil.getInstance().getDesc("NASGetAgentsErrorDesc"));	
		}

		return agents;
	}

	/**
	 * 发送指令
	 * 
	 * @param agentId
	 *            目的agent
	 * @param scriptName
	 *            脚本名称
	 * @param scriptOption
	 *            脚本参数
	 * @param command
	 *            执行脚本的命令，需要绝对路径，python脚本的命令是/usr/bin/python
	 * @param cmdOption
	 *            命令的option
	 * @param action
	 *            "sample"
	 * @param timeout
	 * @return
	 * @throws NCRException
	 */
	public JSONObject sendSyncCommand(String agentId, String scriptName,
			String scriptOption, String command, String cmdOption,
			String action, Map<String, String> map, int timeout)
			throws NCRException {
		JSONObject res = null;
		try {
			logger.info("sendSyncCommand agentId:" + agentId + ",scriptName:" + scriptName + ",scriptOption:" + scriptOption);
			res = client.sendNormalCommand(agentId, scriptName, scriptOption,
					command, cmdOption, action, map, timeout);
			logger.info("sendSyncCommand result:" + res);
		} catch (PomeloClientNotRegisterException e) {
			logger.error("", e);
			// alarm
			alarmService.sendAlarm(AlarmType.AgentExecScriptError, agentId,
					nasConfig.getProduct(), DescriptionUtil.getInstance().getDesc("AgentExecScriptErrorDesc", scriptName + " " + scriptOption, "result:" + res + "," + e.getMessage()));
			throw new NCRException(ErrorCode.NCR_NAS_SEND_FAILURE, e.getMessage());
		} catch (PomeloClientInvalidParameterException e) {
			logger.error("", e);
			// alarm
			alarmService.sendAlarm(AlarmType.AgentExecScriptError, agentId,
					nasConfig.getProduct(), DescriptionUtil.getInstance().getDesc("AgentExecScriptErrorDesc", scriptName + " " + scriptOption, "result:" + res + "," + e.getMessage()));
			throw new NCRException(ErrorCode.NCR_NAS_SEND_FAILURE, e.getMessage());
		}

		return res;
	}
	public void sendAsyncCommand(String agentId, String tag, String scriptName,
			String scriptOption, String command, String cmdOption,
			String action, Map<String, String> map) throws NCRException {
		try {
			logger.info("sendAsyncCommand agentId:" + agentId + ",scriptName:" + scriptName + ",scriptOption:" + scriptOption);
			String messageId = client.sendNormalCommand(agentId, tag,
					scriptName, scriptOption, command, cmdOption, action, map,
					new AckCallback() {
						public void handle(int errorType, String errorMessage) {
							logger.info("ack callback:errorType:" + errorType
									+ ",errorMessage:" + errorMessage);
						}
					});
			// 保存messageId
			logger.info("messageId:" + messageId);
		} catch (PomeloClientNotRegisterException e) {
			logger.error("", e);
			// alarm
			alarmService.sendAlarm(AlarmType.AgentExecScriptError, agentId,
					nasConfig.getProduct(), DescriptionUtil.getInstance().getDesc("AgentExecScriptErrorDesc", scriptName + " " + scriptOption, e.getMessage()));
			throw new NCRException(ErrorCode.NCR_NAS_SEND_FAILURE, e.getMessage());
		} catch (PomeloClientInvalidParameterException e) {
			logger.error("", e);
			// alarm
			alarmService.sendAlarm(AlarmType.AgentExecScriptError, agentId,
					nasConfig.getProduct(), DescriptionUtil.getInstance().getDesc("AgentExecScriptErrorDesc", scriptName + " " + scriptOption, e.getMessage()));
			throw new NCRException(ErrorCode.NCR_NAS_SEND_FAILURE, e.getMessage());
		}
	}

	public String sendScheduledCommand(String agentId, String tag,
			String scriptName, String scriptOption, String command,
			String cmdOption, String action, int execCycle, String execStart,
			int execTimes, JSONObject resultRules, Map<String, String> map,
			AckCallback cb) throws NCRException {
		String ret = null;

		try {
			logger.info("sendScheduledCommand agentId:" + agentId + ",scriptName:" + scriptName + ",scriptOption:" + scriptOption);
			ret = client.sendScheduledCommand(agentId, tag, scriptName,
					scriptOption, command, cmdOption, action, execCycle,
					execStart, execTimes, resultRules, map, cb);
		} catch (PomeloClientNotRegisterException e) {
			logger.error("", e);
			// alarm
			alarmService.sendAlarm(AlarmType.AgentExecScriptError, agentId,
					nasConfig.getProduct(), DescriptionUtil.getInstance().getDesc("AgentExecScriptErrorDesc", scriptName + " " + scriptOption, e.getMessage()));
			throw new NCRException(ErrorCode.NCR_NAS_SEND_FAILURE, e.getMessage());
		} catch (PomeloClientInvalidParameterException e) {
			// alarm
			alarmService.sendAlarm(AlarmType.AgentExecScriptError, agentId,
					nasConfig.getProduct(), DescriptionUtil.getInstance().getDesc("AgentExecScriptErrorDesc", scriptName + " " + scriptOption, e.getMessage()));
			logger.error("", e);
			throw new NCRException(ErrorCode.NCR_NAS_SEND_FAILURE, e.getMessage());
		}

		return ret;
	}
	
	public JSONObject updateScriptCommand(String agentId, String scriptAddress,
			String scriptVersion, Map<String, String> map, int timeout) throws NCRException {
		JSONObject res = null;

		try {
			logger.info("updateScript agentId:" + agentId + ",scriptAddress:" + scriptAddress + ",scriptVersion:" + scriptVersion);
			res = client.updateScript(agentId, scriptAddress, scriptVersion, map, timeout);
		} catch (PomeloClientNotRegisterException e) {
			logger.error("", e);
			// alarm
			alarmService.sendAlarm(AlarmType.AgentExecScriptError, agentId,
					nasConfig.getProduct(), DescriptionUtil.getInstance().getDesc("AgentExecScriptErrorDesc", scriptAddress + " " + scriptVersion, e.getMessage()));
			throw new NCRException(ErrorCode.NCR_NAS_SEND_FAILURE, e.getMessage());
		} catch (PomeloClientInvalidParameterException e) {
			// alarm
			alarmService.sendAlarm(AlarmType.AgentExecScriptError, agentId,
					nasConfig.getProduct(), DescriptionUtil.getInstance().getDesc("AgentExecScriptErrorDesc", scriptAddress + " " + scriptVersion, e.getMessage()));
			logger.error("", e);
			throw new NCRException(ErrorCode.NCR_NAS_SEND_FAILURE, e.getMessage());
		}

		return res;
	}	

	public BaseResult dealResult(String agentId, JSONObject commandResult, String type,
			int instanceCount) throws NCRException {
		logger.debug("dealResult:" + commandResult);
		
		BaseResult baseResult = AgentResultFactory.getResult(DEFAULT_FAIL_CODE,
				instanceCount, commandResult.toString());
		
		try {
			String Status = commandResult.getString(STATUS_NAME);
			if (Status.equals(SUCCESS_STATUS) == true) {
				baseResult.setCode(DEFAULT_SUCCESS_CODE);
			}

			String result = commandResult.getString(RESULT_NAME);

			logger.info(type + " dealResult result:" + result + ",count:"
					+ instanceCount);

			String pattern = "^";
			for (int i = 0; i < instanceCount; ++i) {
				pattern += "code=(\\d+)\\s+message=" + type
						+ "\\s+node\\s+(\\d+)\\s+(\\w+).\\n";
			}
			pattern += "$";
			logger.info("type:" + type + ",pattern:" + pattern + ",result:"
					+ result);

			Pattern r = Pattern.compile(pattern);
			Matcher m = r.matcher(result);
			if (m.find()) {
				for (int i = 1; i <= m.groupCount();) {
					int nodeCode = Integer.parseInt(m.group(i++));
					int nodeSerial = Integer.parseInt(m.group(i++));
					String msg = m.group(i++);
					baseResult.addResult(nodeCode, nodeSerial, msg);
				}
			} else {
				logger.error(type + " dealResult result pattern not match:"
						+ result + ",count:" + instanceCount);
				baseResult.setCode(DEFAULT_FAIL_CODE);
				
				throw new Exception("dealResult result pattern not match,count:" + instanceCount);
			}

			return baseResult;
		} catch (Exception e) {
			logger.error("", e);
			
			// alarm
			alarmService.sendAlarm(AlarmType.AgentExecScriptError, agentId,
					nasConfig.getProduct(), type + " result:" + commandResult + ",Exception:" + e.getMessage());
			
			baseResult.setCode(DEFAULT_FAIL_CODE);
			return baseResult;
		}
	}

	public BaseResult dealCommonResult(String agentId, JSONObject commandResult, String type)
			throws NCRException {
		logger.debug("dealProxyResult:" + commandResult);
		
		BaseResult baseResult = AgentResultFactory.getResult(DEFAULT_FAIL_CODE,
				1, commandResult.toString());

		try {
			String Status = commandResult.getString(STATUS_NAME);
			if (Status.equals(SUCCESS_STATUS) == false) {
				throw new Exception("status false");
			}

			String result = commandResult.getString(RESULT_NAME);

			String pattern = "^code=(\\d+)\\s+message=.+\\.$";

			logger.debug("type:" + type + ",pattern:" + pattern + ",result:"
					+ result);

			Pattern r = Pattern.compile(pattern);
			Matcher m = r.matcher(result);
			if (m.find()) {
				int nodeCode = Integer.parseInt(m.group(1));
				baseResult.setCode(nodeCode);
			} else {
				logger.error(type + " dealCommonResult result pattern not match:"
						+ result);
				
				throw new Exception("dealCommonResult result pattern not match");
			}

			return baseResult;
		} catch (Exception e) {
			logger.error("", e);
			
			// alarm
			alarmService.sendAlarm(AlarmType.AgentExecScriptError, agentId,
					nasConfig.getProduct(), type + " result:" + commandResult + ",Exception:" + e.getMessage());
			
			baseResult.setCode(DEFAULT_FAIL_CODE);
			return baseResult;
		}
	}
	
	public RedisCmdResult dealSentinelResult(String agentId, JSONObject commandResult) throws NCRException {
		logger.debug("dealResult:" + commandResult);
		
		RedisCmdResult sentinelResult = new RedisCmdResult(DEFAULT_FAIL_CODE, "unknow error");
		try {			
			String Status = commandResult.getString(STATUS_NAME);
			if (Status.equals(SUCCESS_STATUS) == false) {
				throw new Exception("status false");
			}

			String result = commandResult.getString(RESULT_NAME);

			logger.info("dealResult result:" + result);
			
			JSONObject jsonResult = new JSONObject(result);
			sentinelResult.setCode(jsonResult.getInt("return_code"));
			sentinelResult.setMsg(jsonResult.getString("return_message"));
			Object data = jsonResult.optJSONArray("data");
			if (data == null) {
				data = jsonResult.optJSONObject("data");
			}
			sentinelResult.setData(data);

		} catch (Exception e) {
			logger.error("", e);
			sentinelResult.setMsg(e.getMessage());
		}
		
		//哨兵定时脚本结果只有未获取到脚本返回结果才报警
		if (sentinelResult.getCode() < NcrConsts.OK) {
			logger.error("returnResult error:" + sentinelResult.getMsg());
			// alarm
//			alarmService.sendAlarm(AlarmType.AgentExecScriptError, agentId,
//					nasConfig.getProduct(), " result:" + commandResult + ",Exception:" + sentinelResult.getMsg());
		}
		
		return sentinelResult;
	}

	public void putCheckMap(String agentId, AgentHeartBeatInfo info) {
		checkMap.put(agentId, info);
	}
	
	public void removeCheckMap(String agentId) {
		checkMap.remove(agentId);
	}
	
	public void putSentinelCheckMap(String agentId, SentinelHeartBeatInfo info) {
		sentinelCheckMap.put(agentId, info);
	}
	
	public void removeSentinelCheckMap(String agentId) {
		checkMap.remove(agentId);
	}	

	public void setReconnectMaxTimes(int reconnectMaxTimes) {
		this.reconnectMaxTimes = reconnectMaxTimes;
	}

	public void setReconnectSleepMillis(int reconnectSleepMillis) {
		this.reconnectSleepMillis = reconnectSleepMillis;
	}

	public void setVmMessageHandle(VmMessageHandle vmMessageHandle) {
		this.vmMessageHandle = vmMessageHandle;
	}

	public void setSentinelHeartBeatHandle(SentinelHeartBeatHandle sentinelHeartBeatHandle) {
		this.sentinelHeartBeatHandle = sentinelHeartBeatHandle;
	}

	public void setAlarmService(IAlarmService alarmService) {
		this.alarmService = alarmService;
	}
}
