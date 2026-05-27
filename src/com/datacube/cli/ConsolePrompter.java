package com.datacube.cli;

import java.util.Scanner;

public class ConsolePrompter {

    private final Scanner scan = new Scanner(System.in);

    public String prompt(String label, String defaultVal, String hint) {
        if (!hint.isEmpty()) System.out.println("    (" + hint + ")");
        System.out.print("  " + label + (defaultVal.isEmpty() ? ": " : " [" + defaultVal + "]: "));
        String input = scan.nextLine().trim();
        return input.isEmpty() ? defaultVal : input;
    }
}
