package com.findback.app;
import org.junit.Test;
import static org.junit.Assert.*;
public class YoloDetectorTest {
    private YoloDetector.Hit box(float x,float y,float w,float h){return new YoloDetector.Hit("bottle",.9f,x,y,w,h);}
    @Test public void identicalBoxesOverlap(){assertEquals(1f,YoloDetector.iou(box(.5f,.5f,.2f,.2f),box(.5f,.5f,.2f,.2f)),.001f);}
    @Test public void separateBoxesRemainDistinct(){assertEquals(0f,YoloDetector.iou(box(.1f,.1f,.1f,.1f),box(.9f,.9f,.1f,.1f)),.001f);}
    @Test public void nestedBoxUsesIntersectionOverUnion(){assertEquals(.25f,YoloDetector.iou(box(.5f,.5f,.4f,.4f),box(.5f,.5f,.2f,.2f)),.001f);}
}
