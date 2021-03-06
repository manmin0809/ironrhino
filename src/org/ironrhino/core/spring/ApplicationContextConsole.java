package org.ironrhino.core.spring;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.apache.commons.lang3.StringUtils;
import org.ironrhino.core.event.EventPublisher;
import org.ironrhino.core.event.ExpressionEvent;
import org.ironrhino.core.metadata.PostPropertiesReset;
import org.ironrhino.core.metadata.Scope;
import org.ironrhino.core.metadata.Trigger;
import org.ironrhino.core.util.AnnotationUtils;
import org.ironrhino.core.util.ApplicationContextUtils;
import org.ironrhino.core.util.ExpressionUtils;
import org.ironrhino.core.util.ReflectionUtils;
import org.mvel2.CompileException;
import org.mvel2.PropertyAccessException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.Lifecycle;
import org.springframework.context.event.EventListener;
import org.springframework.core.convert.ConversionService;
import org.springframework.stereotype.Component;
import org.springframework.util.function.SingletonSupplier;

import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
public class ApplicationContextConsole {

	private static final String PROPERTY_EXPRESSION_PATTERN = "[a-zA-Z][a-zA-Z0-9_\\-]*\\.[a-zA-Z][a-zA-Z0-9_]*";

	private static final String GET_PROPERTY_EXPRESSION_PATTERN = "^" + PROPERTY_EXPRESSION_PATTERN + "$";

	private static final String SET_PROPERTY_EXPRESSION_PATTERN = "^" + PROPERTY_EXPRESSION_PATTERN + "\\s*=\\s*.+$";

	private static final String LIFECYLE_BEAN_EXPRESSION_PATTERN = "^.+\\.(start|stop)\\(\\)$";

	@Autowired
	private ConfigurableListableBeanFactory ctx;

	@Autowired
	private EventPublisher eventPublisher;

	private SingletonSupplier<Map<String, Object>> beansSupplier = SingletonSupplier.of(() -> {
		Map<String, Object> beans = new HashMap<>();
		String[] beanNames = ctx.getBeanDefinitionNames();
		for (String beanName : beanNames) {
			if (!ctx.isSingleton(beanName))
				continue;
			BeanDefinition bd = ctx.getBeanDefinition(beanName);
			if (bd.isAbstract())
				continue;
			if (StringUtils.isAlphanumeric(beanName.replaceAll("_", "")))
				beans.put(beanName, ctx.getBean(beanName));
			String[] aliases = ctx.getAliases(beanName);
			for (String alias : aliases)
				if (StringUtils.isAlphanumeric(alias.replaceAll("_", "")))
					beans.put(alias, ctx.getBean(beanName));
			String factoryBeanName = "&" + beanName;
			if (ctx.containsBean(factoryBeanName))
				beans.put(factoryBeanName.replace('&', '$'), ctx.getBean(factoryBeanName));
		}
		return Collections.unmodifiableMap(beans);
	});

	private SingletonSupplier<Map<String, Scope>> triggersSupplier = SingletonSupplier.of(() -> {
		Map<String, Scope> triggers = new TreeMap<>();
		for (Map.Entry<String, Object> entry : getBeans().entrySet()) {
			Class<?> clz = ReflectionUtils.getTargetObject(entry.getValue()).getClass();
			Set<Method> methods = AnnotationUtils.getAnnotatedMethods(clz, Trigger.class);
			for (Method m : methods) {
				if (m.getParameterCount() == 0) {
					String expression = entry.getKey() + "." + m.getName() + "()";
					triggers.put(expression, m.getAnnotation(Trigger.class).scope());
				}
			}
		}
		return Collections.unmodifiableMap(triggers);
	});

	public Map<String, Object> getBeans() {
		return beansSupplier.obtain();
	}

	public Map<String, Scope> getTriggers() {
		return triggersSupplier.obtain();
	}

	public Map<String, Lifecycle> getLifecycleBeans() {
		Map<String, Lifecycle> beans = new TreeMap<>(ctx.getBeansOfType(Lifecycle.class));
		beans.remove("lifecycleProcessor");
		return Collections.unmodifiableMap(beans);
	}

	public Object execute(String expression, Scope scope) throws Exception {
		expression = expression.trim();
		Object value = null;
		try {
			if (expression.matches(SET_PROPERTY_EXPRESSION_PATTERN)) {
				executeSetProperty(expression);
			} else {
				try {
					value = ExpressionUtils.evalExpression(expression, getBeans());
				} catch (PropertyAccessException pe) {
					if (expression.matches(GET_PROPERTY_EXPRESSION_PATTERN))
						value = executeGetProperty(expression);
					else
						throw pe;
				}
			}
			if (scope != null && scope != Scope.LOCAL)
				eventPublisher.publish(new ExpressionEvent(expression), scope);
			return value;
		} catch (NoSuchFieldException e) {
			throw new IllegalArgumentException("NoSuchFieldException: " + e.getMessage());
		} catch (NoSuchMethodException e) {
			throw new IllegalArgumentException("NoSuchMethodException: " + e.getMessage());
		} catch (CompileException e) {
			if (expression.matches(LIFECYLE_BEAN_EXPRESSION_PATTERN)) {
				int index = expression.lastIndexOf('.');
				String beanName = expression.substring(0, index);
				String methodName = expression.substring(index + 1, expression.lastIndexOf('('));
				Lifecycle bean = (Lifecycle) ctx.getBean(beanName);
				if (methodName.equals("start"))
					bean.start();
				else
					bean.stop();
				return null;
			}
			throw e;
		}
	}

	private void executeSetProperty(String expression) throws Exception {
		Object bean = null;
		String beanName = expression.substring(0, expression.indexOf('.'));
		bean = getBeans().get(beanName);
		if (bean == null)
			throw new IllegalArgumentException("bean[" + beanName + "] doesn't exist");
		try {
			ExpressionUtils.evalExpression(expression, getBeans());
		} catch (PropertyAccessException pe) {
			bean = ReflectionUtils.getTargetObject(bean);
			String fieldName = expression.substring(expression.indexOf('.') + 1, expression.indexOf('=')).trim();
			String value = expression.substring(expression.indexOf('=') + 1).trim();
			Field f = ReflectionUtils.getField(bean.getClass(), fieldName);
			if (f.getType() == String.class && (value.startsWith("\"") && value.endsWith("\"")
					|| value.startsWith("'") && value.endsWith("'"))) {
				value = value.substring(1, value.length() - 1);
			}
			Object v = ApplicationContextUtils.getBean(ConversionService.class).convert(value, f.getType());
			f.set(bean, v);
		}
		ReflectionUtils.processCallback(bean, PostPropertiesReset.class);
	}

	private Object executeGetProperty(String expression) throws Exception {
		Object bean = null;
		String beanName = expression.substring(0, expression.indexOf('.'));
		bean = getBeans().get(beanName);
		if (bean == null)
			throw new IllegalArgumentException("bean[" + beanName + "] doesn't exist");
		bean = ReflectionUtils.getTargetObject(bean);
		String fieldName = expression.substring(expression.indexOf('.') + 1).trim();
		Field f = ReflectionUtils.getField(bean.getClass(), fieldName);
		return f.get(bean);
	}

	@EventListener
	public void onApplicationEvent(ExpressionEvent event) {
		if (event.isLocal())
			return;
		String expression = event.getExpression();
		try {
			execute(expression, Scope.LOCAL);
		} catch (Throwable e) {
			log.error("execute '" + expression + "' error", e);
		}
	}

}