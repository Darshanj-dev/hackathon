package com.findback.app;
import org.junit.Test;
import java.nio.ByteBuffer;
import static org.junit.Assert.*;
public class CameraFramesTest {
 @Test public void preservesChannelsWithRowPadding(){byte[] data={(byte)255,0,0,(byte)255,0,0,0,0,0,(byte)255,0,(byte)255};assertArrayEquals(new int[]{0xffff0000,0xff00ff00},CameraFrames.decode(ByteBuffer.wrap(data),1,2,8,4));}
 @Test public void respectsBufferPosition(){ByteBuffer data=ByteBuffer.wrap(new byte[]{9,9,1,2,3,(byte)255});data.position(2);assertArrayEquals(new int[]{0xff010203},CameraFrames.decode(data,1,1,4,4));}
 @Test(expected=IllegalArgumentException.class) public void rejectsTruncatedFrames(){CameraFrames.decode(ByteBuffer.allocate(3),1,1,4,4);}
}
