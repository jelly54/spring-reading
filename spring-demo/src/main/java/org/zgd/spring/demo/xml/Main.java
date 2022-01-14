package org.zgd.spring.demo.xml;

import org.springframework.context.ApplicationContext;
import org.springframework.context.support.ClassPathXmlApplicationContext;

/**
 * @author zhangguodong
 * @since 2022/1/13 17:29
 */
public class Main {
	public static void main(String[] args) {

		ApplicationContext context = new ClassPathXmlApplicationContext("beans.xml");
		Person obj = (Person) context.getBean("person");
		System.out.println(obj.getName());
	}
}
