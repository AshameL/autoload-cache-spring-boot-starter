package com.jarvis.cache.redis;

import java.io.UnsupportedEncodingException;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.jedis.JedisConnectionFactory;
import org.springframework.data.redis.core.RedisConnectionUtils;

import com.jarvis.cache.ICacheManager;
import com.jarvis.cache.clone.ICloner;
import com.jarvis.cache.exception.CacheCenterConnectionException;
import com.jarvis.cache.serializer.ISerializer;
import com.jarvis.cache.serializer.StringSerializer;
import com.jarvis.cache.to.AutoLoadConfig;
import com.jarvis.cache.to.CacheKeyTO;
import com.jarvis.cache.to.CacheWrapper;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.Pipeline;

/**
 * Redis缓存管理
 * @author jiayu.qiu
 */
public class SpringJedisCacheManager implements ICacheManager {

    private static final Logger logger=LoggerFactory.getLogger(SpringJedisCacheManager.class);

    private static final StringSerializer keySerializer=new StringSerializer();

    private final ISerializer<Object> serializer;

    private final ICloner cloner;

    private final AutoLoadConfig config;

    private JedisConnectionFactory redisConnectionFactory;

    /**
     * Hash的缓存时长：等于0时永久缓存；大于0时，主要是为了防止一些已经不用的缓存占用内存;hashExpire小于0时，则使用@Cache中设置的expire值（默认值为-1）。
     */
    private int hashExpire=-1;

    /**
     * 是否通过脚本来设置 Hash的缓存时长
     */
    private boolean hashExpireByScript=false;

    public SpringJedisCacheManager(AutoLoadConfig config, ISerializer<Object> serializer) {
        this.config=config;
        this.serializer=serializer;
        this.cloner=serializer;
    }

    public JedisConnectionFactory getRedisConnectionFactory() {
        return redisConnectionFactory;
    }

    public void setRedisConnectionFactory(JedisConnectionFactory redisConnectionFactory) {
        this.redisConnectionFactory=redisConnectionFactory;
    }

    @Override
    public void setCache(final CacheKeyTO cacheKeyTO, final CacheWrapper<Object> result, final Method method, final Object args[]) throws CacheCenterConnectionException {
        if(null == redisConnectionFactory || null == cacheKeyTO) {
            return;
        }
        String cacheKey=cacheKeyTO.getCacheKey();
        if(null == cacheKey || cacheKey.length() == 0) {
            return;
        }
        RedisConnection redisConnection=RedisConnectionUtils.getConnection(redisConnectionFactory);
        try {
            Jedis jedis=(Jedis)redisConnection.getNativeConnection();
            int expire=result.getExpire();
            String hfield=cacheKeyTO.getHfield();
            if(null == hfield || hfield.length() == 0) {
                if(expire == 0) {
                    jedis.set(keySerializer.serialize(cacheKey), getSerializer().serialize(result));
                } else if(expire > 0) {
                    jedis.setex(keySerializer.serialize(cacheKey), expire, getSerializer().serialize(result));
                }
            } else {
                hashSet(jedis, cacheKey, hfield, result);
            }
        } catch(Exception ex) {
            logger.error(ex.getMessage(), ex);
        } finally {
            RedisConnectionUtils.releaseConnection(redisConnection, redisConnectionFactory);
        }
    }

    private static byte[] hashSetScript;

    static {
        try {
            String tmpScript="redis.call('HSET', KEYS[1], KEYS[2], ARGV[1]);\nredis.call('EXPIRE', KEYS[1], tonumber(ARGV[2]));";
            hashSetScript=tmpScript.getBytes("UTF-8");
        } catch(UnsupportedEncodingException ex) {
            logger.error(ex.getMessage(), ex);
        }
    }

    private static final Map<Jedis, byte[]> hashSetScriptSha=new ConcurrentHashMap<Jedis, byte[]>();

    private void hashSet(Jedis jedis, String cacheKey, String hfield, CacheWrapper<Object> result) throws Exception {
        byte[] key=keySerializer.serialize(cacheKey);
        byte[] field=keySerializer.serialize(hfield);
        byte[] val=getSerializer().serialize(result);
        int hExpire;
        if(hashExpire < 0) {
            hExpire=result.getExpire();
        } else {
            hExpire=hashExpire;
        }
        if(hExpire == 0) {
            jedis.hset(key, field, val);
        } else if(hExpire > 0) {
            if(hashExpireByScript) {
                byte[] sha=hashSetScriptSha.get(jedis);
                if(null == sha) {
                    sha=jedis.scriptLoad(hashSetScript);
                    hashSetScriptSha.put(jedis, sha);
                }
                List<byte[]> keys=new ArrayList<byte[]>();
                keys.add(key);
                keys.add(field);

                List<byte[]> args=new ArrayList<byte[]>();
                args.add(val);
                args.add(keySerializer.serialize(String.valueOf(hExpire)));
                try {
                    jedis.evalsha(sha, keys, args);
                } catch(Exception ex) {
                    logger.error(ex.getMessage(), ex);
                    try {
                        sha=jedis.scriptLoad(hashSetScript);
                        hashSetScriptSha.put(jedis, sha);
                        jedis.evalsha(sha, keys, args);
                    } catch(Exception ex1) {
                        logger.error(ex1.getMessage(), ex1);
                    }
                }
            } else {
                Pipeline p=jedis.pipelined();
                p.hset(key, field, val);
                p.expire(key, hExpire);
                p.sync();
            }
        }
    }

    @SuppressWarnings("unchecked")
    @Override
    public CacheWrapper<Object> get(final CacheKeyTO cacheKeyTO, final Method method, final Object args[]) throws CacheCenterConnectionException {
        if(null == redisConnectionFactory || null == cacheKeyTO) {
            return null;
        }
        String cacheKey=cacheKeyTO.getCacheKey();
        if(null == cacheKey || cacheKey.length() == 0) {
            return null;
        }
        CacheWrapper<Object> res=null;
        RedisConnection redisConnection=RedisConnectionUtils.getConnection(redisConnectionFactory);
        try {
            Jedis jedis=(Jedis)redisConnection.getNativeConnection();
            byte bytes[]=null;
            String hfield=cacheKeyTO.getHfield();
            if(null == hfield || hfield.length() == 0) {
                bytes=jedis.get(keySerializer.serialize(cacheKey));
            } else {
                bytes=jedis.hget(keySerializer.serialize(cacheKey), keySerializer.serialize(hfield));
            }
            Type returnType=method.getGenericReturnType();
            res=(CacheWrapper<Object>)getSerializer().deserialize(bytes, returnType);
        } catch(Exception ex) {
            logger.error(ex.getMessage(), ex);
        } finally {
            RedisConnectionUtils.releaseConnection(redisConnection, redisConnectionFactory);
        }
        return res;
    }

    /**
     * 根据缓存Key删除缓存
     * @param cacheKeyTO 缓存Key
     */
    @Override
    public void delete(CacheKeyTO cacheKeyTO) throws CacheCenterConnectionException {
        if(null == redisConnectionFactory || null == cacheKeyTO) {
            return;
        }
        String cacheKey=cacheKeyTO.getCacheKey();
        if(null == cacheKey || cacheKey.length() == 0) {
            return;
        }
        logger.debug("delete cache:" + cacheKey);
        RedisConnection redisConnection=RedisConnectionUtils.getConnection(redisConnectionFactory);
        try {
            Jedis jedis=(Jedis)redisConnection.getNativeConnection();
            if("*".equals(cacheKey)) {
                jedis.flushDB();
            } else if(cacheKey.indexOf("*") != -1) {
                // 如果传进来的值中 带有 * 或 ? 号，则会使用批量删除（遍历所有Redis服务器）,性能非常差，不建议使用这种方法。
                // 建议使用 hash表方缓存需要批量删除的数据。
                batchDel(jedis, cacheKey);
            } else {
                String hfield=cacheKeyTO.getHfield();
                if(null == hfield || hfield.length() == 0) {
                    jedis.del(keySerializer.serialize(cacheKey));
                } else {
                    jedis.hdel(keySerializer.serialize(cacheKey), keySerializer.serialize(hfield));
                }
            }
        } catch(Exception ex) {
            logger.error(ex.getMessage(), ex);
        } finally {
            RedisConnectionUtils.releaseConnection(redisConnection, redisConnectionFactory);
        }
    }

    private static byte[] delScript;

    static {
        StringBuilder tmp=new StringBuilder();
        tmp.append("local keys = redis.call('keys', KEYS[1]);\n");
        tmp.append("if(not keys or #keys == 0) then \n return nil; \n end \n");
        tmp.append("redis.call('del', unpack(keys)); \n return keys;");
        try {
            delScript=tmp.toString().getBytes("UTF-8");
        } catch(UnsupportedEncodingException ex) {
            logger.error(ex.getMessage(), ex);
        }
    }

    private static final Map<Jedis, byte[]> delScriptSha=new ConcurrentHashMap<Jedis, byte[]>();

    private void batchDel(Jedis jedis, String cacheKey) throws Exception {
        byte[] sha=delScriptSha.get(jedis);
        byte[] key=keySerializer.serialize(cacheKey);
        if(null == sha) {
            sha=jedis.scriptLoad(delScript);
            delScriptSha.put(jedis, sha);
        }
        try {
            @SuppressWarnings("unchecked")
            List<String> keys=(List<String>)jedis.evalsha(sha, 1, key);
            if(null != keys && keys.size() > 0) {
                /*
                 * for(String tmpKey: keys) { autoLoadHandler.resetAutoLoadLastLoadTime(tmpKey); }
                 */
            }
        } catch(Exception ex) {
            logger.error(ex.getMessage(), ex);
            try {
                sha=jedis.scriptLoad(delScript);
                delScriptSha.put(jedis, sha);
                @SuppressWarnings("unchecked")
                List<String> keys=(List<String>)jedis.evalsha(sha, 1, key);
                if(null != keys && keys.size() > 0) {
                    /*
                     * for(String tmpKey: keys) { autoLoadHandler.resetAutoLoadLastLoadTime(tmpKey); }
                     */
                }
            } catch(Exception ex1) {
                logger.error(ex1.getMessage(), ex1);
            }
        }
    }

    public int getHashExpire() {
        return hashExpire;
    }

    public void setHashExpire(int hashExpire) {
        if(hashExpire < 0) {
            return;
        }
        this.hashExpire=hashExpire;
    }

    public boolean isHashExpireByScript() {
        return hashExpireByScript;
    }

    public void setHashExpireByScript(boolean hashExpireByScript) {
        this.hashExpireByScript=hashExpireByScript;
    }

    @Override
    public ICloner getCloner() {
        return this.cloner;
    }

    @Override
    public ISerializer<Object> getSerializer() {
        return this.serializer;
    }

    @Override
    public AutoLoadConfig getAutoLoadConfig() {
        return this.config;
    }
}
