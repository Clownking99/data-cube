package com.datacube.cli;

import java.util.Scanner;

public class ConsolePrompter {

    private final Scanner scan = new Scanner(System.in);

    public String secret(String label) {
        java.io.Console console=System.console();
        if(console==null) {
            System.out.println("当前终端不支持隐藏输入；可退出并使用桌面密码框。");
            return prompt(label,"","");
        }
        char[] value=console.readPassword("  %s: ",label);
        if(value==null)return "";
        try{return new String(value);}finally{java.util.Arrays.fill(value,'\0');}
    }

    public String prompt(String label, String defaultVal, String hint) {
        if (!hint.isEmpty()) System.out.println("    (" + hint + ")");
        System.out.print("  " + label + (defaultVal.isEmpty() ? ": " : " [" + defaultVal + "]: "));
        String input = scan.nextLine().trim();
        return input.isEmpty() ? defaultVal : input;
    }
}
