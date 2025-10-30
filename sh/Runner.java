package com.example.attempt;


public class Runner {
    
    public static void main(String[] args) {
        System.out.println("=".repeat(60));
        System.out.println("Quick Start !!!");
        System.out.println("=".repeat(60));
        
        try {
            System.out.println("\n[1/3] Main.java ...");
            System.out.println("-".repeat(60));
            Main.main(args);
            System.out.println("-".repeat(60));
            System.out.println("✓ Main.java Finished\n");
            
            System.out.println("[2/3] Match.java ...");
            System.out.println("-".repeat(60));
            Match.main(args);
            System.out.println("-".repeat(60));
            System.out.println("✓ Match.java Finished\n");
            
            System.out.println("[3/3] Fill.java ...");
            System.out.println("-".repeat(60));
            Fill.main(args);
            System.out.println("-".repeat(60));
            System.out.println("✓ Fill.java Finished\n");
            
            System.out.println("=".repeat(60));
            System.out.println("SUCCESS ALL Finished !!!");
            System.out.println("=".repeat(60));
            
        } catch (Exception e) {
            System.err.println("\n❌ ERROR!!!!!!!!");
            e.printStackTrace();
            System.exit(1);
        }
    }
}
