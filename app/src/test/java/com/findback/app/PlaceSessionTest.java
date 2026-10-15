package com.findback.app;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;
public class PlaceSessionTest {
 private PlaceSession.Evidence label(String text,long at){return new PlaceSession.Evidence(text,"centre",at,PlaceSession.hasDirectionalText(text));}
 @Test public void plainOcrAloneCannotCompleteTask(){PlaceSession s=new PlaceSession();s.begin();s.observe(List.of(label("Washroom",1000)),1000,false);assertEquals(PlaceSession.State.CANDIDATE,s.state());assertFalse(s.canConfirm(1000));}
 @Test public void arrowSignNeverBecomesEntranceConfirmation(){PlaceSession s=new PlaceSession();s.begin();s.observe(List.of(label("Washrooms →",1000)),1000,true);assertEquals(PlaceSession.State.CANDIDATE,s.state());assertFalse(s.canConfirm(1000));}
 @Test public void newVerificationAndUserAcknowledgmentCompleteTask(){PlaceSession s=new PlaceSession();s.begin();s.observe(List.of(label("Washroom",1000)),1000,false);assertTrue(s.requestVerification(1100));s.observe(List.of(label("Washroom",2000)),2000,true);assertTrue(s.canConfirm(2000));assertTrue(s.confirm(2100));assertEquals(PlaceSession.State.CONFIRMED,s.state());assertTrue(s.evidence().isEmpty());}
 @Test public void hiddenSignWithdrawsConfirmation(){PlaceSession s=new PlaceSession();s.begin();s.observe(List.of(label("Washroom",500)),500,false);s.requestVerification(600);s.observe(List.of(label("Washroom",1000)),1000,true);s.observe(List.of(label("Exit",2000)),2000,true);assertEquals(PlaceSession.State.NEEDS_RESCAN,s.state());assertFalse(s.canConfirm(2000));}
 @Test public void staleFrameIsRejected(){PlaceSession s=new PlaceSession();s.begin();s.observe(List.of(label("Washroom",1000)),10000,true);assertFalse(s.canConfirm(10000));assertEquals(PlaceSession.State.NEEDS_RESCAN,s.state());}
 @Test public void expiredAcknowledgmentIsRejected(){PlaceSession s=new PlaceSession();s.begin();s.observe(List.of(label("Washroom",500)),500,false);s.requestVerification(600);s.observe(List.of(label("Washroom",1000)),1000,true);assertFalse(s.confirm(10000));}
 @Test public void competingSignsRequireRescan(){PlaceSession s=new PlaceSession();s.begin();s.observe(List.of(label("Men toilets",1000),label("Women toilets",1000)),1000,true);assertEquals(PlaceSession.State.NEEDS_RESCAN,s.state());}
 @Test public void movementInvalidatesCurrentObservation(){PlaceSession s=new PlaceSession();s.begin();s.observe(List.of(label("Washroom",500)),500,false);s.requestVerification(600);s.observe(List.of(label("Washroom",1000)),1000,true);s.invalidate("Camera moved");assertFalse(s.canConfirm(1001));}
 @Test public void verificationWithoutPriorCandidateIsRejected(){PlaceSession s=new PlaceSession();s.begin();s.observe(List.of(label("Washroom",1000)),1000,true);assertFalse(s.canConfirm(1000));}
 @Test public void reusingSameFrameCannotVerifyEntrance(){PlaceSession s=new PlaceSession();s.begin();s.observe(List.of(label("Washroom",1000)),1000,false);assertTrue(s.requestVerification(1100));s.observe(List.of(label("Washroom",1000)),1100,true);assertFalse(s.canConfirm(1100));}
 @Test public void cancellingClearsSessionEvidence(){PlaceSession s=new PlaceSession();s.begin();s.observe(List.of(label("Washroom",1000)),1000,false);s.cancel();assertTrue(s.evidence().isEmpty());assertEquals(PlaceSession.State.IDLE,s.state());}
}
