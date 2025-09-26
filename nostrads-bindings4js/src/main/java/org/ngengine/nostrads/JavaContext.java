package org.ngengine.nostrads;

public class JavaContext {
    public JavaContext(){
    }
    
    public  void run(Runnable r){
        new Thread(()->{
            synchronized(this){
                r.run();
            }
        }).start();
    }
}
