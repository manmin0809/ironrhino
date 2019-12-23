package org.ironrhino.core.cache.impl;

import static org.ironrhino.core.metadata.Profiles.CLOUD;
import static org.ironrhino.core.metadata.Profiles.DUAL;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import javax.annotation.PostConstruct;

import org.apache.commons.lang3.StringUtils;
import org.ironrhino.core.cache.CacheManager;
import org.ironrhino.core.spring.configuration.PriorityQualifier;
import org.ironrhino.core.spring.configuration.ServiceImplementationConditional;
import org.ironrhino.core.spring.data.redis.FallbackToStringSerializer;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.SerializationException;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;

import lombok.extern.slf4j.Slf4j;

@SuppressWarnings({ "unchecked", "rawtypes" })
@Component("cacheManager")
@ServiceImplementationConditional(profiles = { DUAL, CLOUD })
@Slf4j
public class RedisCacheManager implements CacheManager {

	public static final String DEFAULT_SERIALIZER = "redisCacheManager.defaultSerializer";

	public static final String SERIALIZERS_PREFIX = "redisCacheManager.serializers.";

	public static final String TEMPLATES_PREFIX = "redisCacheManager.templates.";

	@Autowired
	@PriorityQualifier
	private RedisTemplate cacheRedisTemplate;

	@Autowired
	@Qualifier("stringRedisTemplate")
	@PriorityQualifier
	private StringRedisTemplate cacheStringRedisTemplate;

	@Autowired
	private ApplicationContext ctx;

	private Map<String, RedisTemplate> cache = new ConcurrentHashMap<>();

	private RedisScript<Long> incrementAndExpireScript = new DefaultRedisScript<>(
			"local v=redis.call('incrby',KEYS[1],ARGV[1]) redis.call('pexpire',KEYS[1],ARGV[2]) return v", Long.class);

	private RedisScript<Long> decrementPositiveScript = new DefaultRedisScript<>(
			"if redis.call('exists',KEYS[1])==1 then local v=redis.call('decrby',KEYS[1],ARGV[1]) if v >= 0 then if tonumber(ARGV[2]) > 0 then redis.call('pexpire',KEYS[1],ARGV[2]) end return v else redis.call('incrby',KEYS[1],ARGV[1]) return -2 end else return -1 end",
			Long.class);

	@PostConstruct
	public void init() {
		String defaultSerializerClass = ctx.getEnvironment().getProperty(DEFAULT_SERIALIZER);
		if (StringUtils.isNotBlank(defaultSerializerClass)) {
			try {
				cacheRedisTemplate.setValueSerializer((RedisSerializer) BeanUtils.instantiateClass(
						ClassUtils.forName(defaultSerializerClass, RedisCacheManager.class.getClassLoader())));
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		} else {
			cacheRedisTemplate.setValueSerializer(new FallbackToStringSerializer());
		}
	}

	@Override
	public void put(String key, Object value, int timeToLive, TimeUnit timeUnit, String namespace) {
		if (value == null)
			throw new IllegalArgumentException("value should not be null");
		String actualKey = generateKey(key, namespace);
		RedisTemplate redisTemplate = findRedisTemplate(namespace);
		try {
			if (timeToLive > 0)
				redisTemplate.opsForValue().set(actualKey, value, timeToLive, timeUnit);
			else
				redisTemplate.opsForValue().set(actualKey, value);
		} catch (Exception e) {
			log.error(e.getMessage(), e);
		}
	}

	@Override
	public boolean exists(String key, String namespace) {
		String actualKey = generateKey(key, namespace);
		try {
			Boolean b = findRedisTemplate(namespace).hasKey(actualKey);
			return b != null && b;
		} catch (Exception e) {
			log.error(e.getMessage(), e);
			return false;
		}
	}

	@Override
	public Object get(String key, String namespace) {
		String actualKey = generateKey(key, namespace);
		try {
			return findRedisTemplate(namespace).opsForValue().get(actualKey);
		} catch (SerializationException e) {
			log.warn(e.getMessage());
			delete(key, namespace);
			return null;
		} catch (Exception e) {
			log.error(e.getMessage(), e);
			return null;
		}
	}

	@Override
	public Object getWithTti(String key, String namespace, int timeToIdle, TimeUnit timeUnit) {
		String actualKey = generateKey(key, namespace);
		RedisTemplate redisTemplate = findRedisTemplate(namespace);
		try {
			Object result = redisTemplate.opsForValue().get(actualKey);
			if (result != null && timeToIdle > 0)
				redisTemplate.expire(actualKey, timeToIdle, timeUnit);
			return result;
		} catch (SerializationException e) {
			log.warn(e.getMessage());
			delete(key, namespace);
			return null;
		} catch (Exception e) {
			log.error(e.getMessage(), e);
			return null;
		}
	}

	@Override
	public long ttl(String key, String namespace) {
		String actualKey = generateKey(key, namespace);
		Long value = findRedisTemplate(namespace).getExpire(actualKey, TimeUnit.MILLISECONDS);
		if (value == null)
			value = -1L;
		if (value == -2)
			value = -2L; // not exists
		return value;
	}

	@Override
	public void setTtl(String key, String namespace, int timeToLive, TimeUnit timeUnit) {
		if (timeToLive <= 0)
			throw new IllegalArgumentException("timeToLive should be postive");
		findRedisTemplate(namespace).expire(generateKey(key, namespace), timeToLive, timeUnit);
	}

	@Override
	public void delete(String key, String namespace) {
		String actualKey = generateKey(key, namespace);
		try {
			findRedisTemplate(namespace).delete(actualKey);
		} catch (Exception e) {
			log.error(e.getMessage(), e);
		}
	}

	@Override
	public void mput(Map<String, Object> map, final int timeToLive, TimeUnit timeUnit, String namespace) {
		if (map == null)
			throw new IllegalArgumentException("map should not be null");
		RedisTemplate redisTemplate = findRedisTemplate(namespace);
		try {
			Map<String, Object> temp = new HashMap<>();
			map.forEach((key, value) -> temp.put(generateKey(key, namespace), value));
			redisTemplate.opsForValue().multiSet(temp);
			if (timeToLive > 0)
				temp.keySet().forEach(key -> redisTemplate.expire(key, timeToLive, timeUnit));
		} catch (Exception e) {
			log.error(e.getMessage(), e);
		}
	}

	@Override
	public Map<String, Object> mget(Collection<String> keys, String namespace) {
		if (keys == null)
			throw new IllegalArgumentException("keys should not be null");
		keys = keys.stream().filter(StringUtils::isNotBlank).collect(Collectors.toCollection(HashSet::new));
		try {
			List<Object> list = findRedisTemplate(namespace).opsForValue()
					.multiGet(keys.stream().map(key -> generateKey(key, namespace)).collect(Collectors.toList()));
			Map<String, Object> result = new HashMap<>();
			int i = 0;
			for (String key : keys) {
				result.put(key, list.get(i));
				i++;
			}
			return result;
		} catch (Exception e) {
			log.error(e.getMessage(), e);
			return null;
		}
	}

	@Override
	public void mdelete(Collection<String> keys, final String namespace) {
		if (keys == null)
			throw new IllegalArgumentException("keys should not be null");
		try {
			findRedisTemplate(namespace).delete(keys.stream().filter(StringUtils::isNotBlank)
					.map(key -> generateKey(key, namespace)).collect(Collectors.toList()));
		} catch (Exception e) {
			log.error(e.getMessage(), e);
		}
	}

	@Override
	public boolean putIfAbsent(String key, Object value, int timeToLive, TimeUnit timeUnit, String namespace) {
		if (value == null)
			throw new IllegalArgumentException("value should not be null");
		String actualkey = generateKey(key, namespace);
		RedisTemplate redisTemplate = findRedisTemplate(namespace);
		Boolean result;
		if (timeToLive > 0) {
			result = redisTemplate.opsForValue().setIfAbsent(actualkey, value, timeToLive, timeUnit);
		} else {
			result = redisTemplate.opsForValue().setIfAbsent(actualkey, value);
		}
		if (result == null)
			throw new RuntimeException("Unexpected null");
		return result;
	}

	@Override
	public long increment(String key, long delta, int timeToLive, TimeUnit timeUnit, String namespace) {
		String actualkey = generateKey(key, namespace);
		RedisTemplate redisTemplate = findRedisTemplate(namespace);
		Long result;
		if (timeToLive > 0) {
			result = (Long) redisTemplate.execute(incrementAndExpireScript, redisTemplate.getStringSerializer(),
					redisTemplate.getValueSerializer(), Collections.singletonList(actualkey), String.valueOf(delta),
					String.valueOf(timeUnit.toMillis(timeToLive)));
		} else {
			result = redisTemplate.opsForValue().increment(actualkey, delta);
		}
		if (result == null)
			throw new RuntimeException("Unexpected null");
		return result;
	}

	@Override
	public long decrementAndReturnNonnegative(String key, long delta, int timeToLive, TimeUnit timeUnit,
			String namespace) {
		if (delta <= 0)
			throw new IllegalArgumentException("delta should great than 0");
		RedisTemplate redisTemplate = findRedisTemplate(namespace);
		String actualkey = generateKey(key, namespace);
		Long result = (Long) redisTemplate.execute(decrementPositiveScript, redisTemplate.getStringSerializer(),
				redisTemplate.getValueSerializer(), Collections.singletonList(actualkey), String.valueOf(delta),
				String.valueOf(timeUnit.toMillis(timeToLive)));
		if (result == null)
			throw new RuntimeException("Unexpected null");
		if (result == -1)
			throw new IllegalStateException("namespace:" + namespace + ", key:" + key + " does not exist");
		if (result == -2)
			throw new IllegalStateException("namespace:" + namespace + ", key:" + key + " is less than " + delta);
		return result;
	}

	private String generateKey(String key, String namespace) {
		if (key == null)
			throw new IllegalArgumentException("key should not be null");
		if (StringUtils.isNotBlank(namespace)) {
			return namespace + ':' + key;
		} else {
			return key;
		}
	}

	@Override
	public boolean supportsGetTtl() {
		return true;
	}

	@Override
	public boolean supportsUpdateTtl() {
		return true;
	}

	public void invalidate(String namespace) {
		RedisScript<Boolean> script = new DefaultRedisScript<>(
				"local keys = redis.call('keys', ARGV[1]) \n for i=1,#keys,5000 do \n redis.call('del', unpack(keys, i, math.min(i+4999, #keys))) \n end \n return true",
				Boolean.class);
		cacheStringRedisTemplate.execute(script, Collections.emptyList(), namespace + ":*");
	}

	protected RedisTemplate findRedisTemplate(String namespace) {
		if (StringUtils.isBlank(namespace))
			return cacheRedisTemplate;
		String templateBeanName = ctx.getEnvironment().getProperty(TEMPLATES_PREFIX + namespace);
		if (StringUtils.isNotBlank(templateBeanName))
			return ctx.getBean(templateBeanName, RedisTemplate.class);
		String serializerClass = ctx.getEnvironment().getProperty(SERIALIZERS_PREFIX + namespace);
		if (StringUtils.isBlank(serializerClass))
			return cacheRedisTemplate;
		return cache.computeIfAbsent(namespace, key -> {
			RedisTemplate rt = new RedisTemplate();
			BeanUtils.copyProperties(cacheRedisTemplate, rt);
			try {
				rt.setValueSerializer((RedisSerializer) BeanUtils.instantiateClass(
						ClassUtils.forName(serializerClass, RedisCacheManager.class.getClassLoader())));
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
			rt.afterPropertiesSet();
			return rt;
		});
	}

}
