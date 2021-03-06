package org.ironrhino.core.util;

import java.util.Comparator;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

public class ClassComparator implements Comparator<Class<?>> {

	public final static ClassComparator INSTANCE = new ClassComparator();

	@Override
	public int compare(Class<?> a, Class<?> b) {
		int order1 = Ordered.LOWEST_PRECEDENCE, order2 = Ordered.LOWEST_PRECEDENCE;
		Order o = org.springframework.core.annotation.AnnotationUtils.getAnnotation(a, Order.class);
		if (o != null)
			order1 = o.value();
		o = org.springframework.core.annotation.AnnotationUtils.getAnnotation(b, Order.class);
		if (o != null)
			order2 = o.value();
		int v = Integer.compare(order1, order2);
		if (v != 0)
			return v;
		v = b.getName().split("\\.").length - a.getName().split("\\.").length;
		if (v != 0)
			return v;
		return a.getName().compareTo(b.getName());
	}

}
