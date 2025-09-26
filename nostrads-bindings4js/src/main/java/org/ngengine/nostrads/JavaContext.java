package org.ngengine.nostrads;

import java.util.ArrayList;
import java.util.List;

public class JavaContext {
    private static List<Runnable> queue = new ArrayList<>();
    private static boolean started = false;

    public static void loop(){
        while(true){
            Runnable r = null;
            synchronized (queue){
                if (queue.isEmpty()){
                    try {
                        queue.wait();
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                }
                if (!queue.isEmpty()){
                    r = queue.remove(0);
                }
            }
            if (r != null){
                try {
                    r.run();
                } catch (Throwable t){
                    t.printStackTrace();
                }
            }
        }
    }

    public static void run(Runnable r){
        new Thread(()->{
            synchronized (queue){
                if(!started){
                    started = true;
                    new Thread(JavaContext::loop).start();
                }
                queue.add(r);
                queue.notifyAll();
            }
        }).start();

    }
}
