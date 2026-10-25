package com.example;

public class App {
    public String greeting(String name) {
        if (name == null || name.isBlank()) {
            return "Hello, world!";
        }
        return "Hello, " + name.trim() + "!";
    }

    public static void main(String[] args) {
        System.out.println(new App().greeting("SonarQube"));
    }
}
