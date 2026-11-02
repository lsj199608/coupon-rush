package com.couponrush;

import org.springframework.boot.SpringApplication;

public class TestCouponRushApplication {

	public static void main(String[] args) {
		SpringApplication.from(CouponRushApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
