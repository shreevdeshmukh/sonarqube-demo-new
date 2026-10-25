package com.example;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class AppTest {
    @Test
    public void returnsGreetingForName() {
        assertEquals("Hello, Jenkins!", new App().greeting("Jenkins"));
    }

    @Test
    public void returnsDefaultGreetingForBlankName() {
        assertEquals("Hello, world!", new App().greeting("   "));
    }
}
