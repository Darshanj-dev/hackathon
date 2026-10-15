package com.findback.app;

import java.util.*;
import java.util.regex.Pattern;

/** Task policy uses fresh OCR and explicit acknowledgment, never generated walking directions. */
public final class PlaceSession {
    public static final long TTL_MS=8000;
    public enum State { IDLE, SEARCHING, CANDIDATE, VERIFYING, NEEDS_RESCAN, CONFIRMED, PAUSED }
    public record Evidence(String text,String side,long capturedAt,boolean directional) {}
    private static final Pattern TARGET=Pattern.compile("\\b(washrooms?|restrooms?|toilets?)\\b",Pattern.CASE_INSENSITIVE);
    private static final Pattern DIRECTIONS=Pattern.compile("[←→↑↓↗↘↙↖]|->|<-|\\b(left|right|ahead|floor|upstairs|downstairs|towards|follow)\\b",Pattern.CASE_INSENSITIVE);
    private final Deque<Evidence> ledger=new ArrayDeque<>();
    private State state=State.IDLE;
    private Evidence current;
    private long verificationAfter=-1;
    private String message="Choose Washroom and scan a sign while stationary.";
    public synchronized State state(){return state;}
    public synchronized String message(){return message;}
    public synchronized List<Evidence> evidence(){return new ArrayList<>(ledger);}
    public synchronized void begin(){ledger.clear();current=null;verificationAfter=-1;state=State.SEARCHING;message="Searching for washroom signs. Hold the phone steady and scan while stationary.";}
    public synchronized void observe(List<Evidence> observations,long now,boolean verification){
        current=null;
        List<Evidence> matching=new ArrayList<>();
        for(Evidence e:observations)if(TARGET.matcher(e.text()).find()&&now-e.capturedAt()<=TTL_MS&&now>=e.capturedAt())matching.add(e);
        if(matching.isEmpty()){state=State.NEEDS_RESCAN;message="No fresh washroom label is visible in this scan. Aim at a readable sign and scan again.";return;}
        if(matching.size()>1){state=State.NEEDS_RESCAN;message="More than one washroom label is visible. Aim at one sign or entrance and scan again.";return;}
        Evidence e=matching.get(0);current=e;
        if(ledger.isEmpty()||!ledger.getLast().text().equals(e.text())||ledger.getLast().capturedAt()!=e.capturedAt()){ledger.addLast(e);while(ledger.size()>6)ledger.removeFirst();}
        if(e.directional()||DIRECTIONS.matcher(e.text()).find()){
            state=State.CANDIDATE;message="Washroom direction sign, "+e.side()+" of the camera view. Arrow unverified; entrance not confirmed.";
        }else if(verification&&verificationAfter>=0&&e.capturedAt()>verificationAfter){verificationAfter=-1;state=State.VERIFYING;message="Fresh label: "+e.text()+". Confirm only if this is the entrance you wanted.";}
        else{state=State.CANDIDATE;message="Washroom text, "+e.side()+" of camera view. Aim at the entrance label and tap Verify entrance.";}
    }
    public synchronized boolean requestVerification(long now){if(!canVerify(now))return false;verificationAfter=current.capturedAt();current=null;state=State.SEARCHING;return true;}
    public synchronized boolean canVerify(long now){return state==State.CANDIDATE&&fresh(now);}
    public synchronized boolean canConfirm(long now){return state==State.VERIFYING&&fresh(now);}
    public synchronized boolean confirm(long now){if(!canConfirm(now)){invalidate("That observation is no longer current. Scan the entrance again.");return false;}state=State.CONFIRMED;message="You confirmed the washroom entrance. Task complete.";current=null;ledger.clear();return true;}
    public synchronized boolean expire(long now){if((state==State.CANDIDATE||state==State.VERIFYING)&&!fresh(now)){invalidate("The last observation is out of date. Scan the sign again.");return true;}return false;}
    public synchronized void invalidate(String reason){if(state==State.IDLE||state==State.CONFIRMED||state==State.PAUSED)return;current=null;verificationAfter=-1;state=State.NEEDS_RESCAN;message=reason;}
    public synchronized void pause(){if(state!=State.IDLE&&state!=State.CONFIRMED){current=null;verificationAfter=-1;state=State.PAUSED;}}
    public synchronized void cancel(){current=null;verificationAfter=-1;ledger.clear();state=State.IDLE;message="Task cancelled. Recent sign observations cleared.";}
    private boolean fresh(long now){return current!=null&&now>=current.capturedAt()&&now-current.capturedAt()<=TTL_MS;}
    public static boolean hasDirectionalText(String text){return DIRECTIONS.matcher(text).find();}
}
