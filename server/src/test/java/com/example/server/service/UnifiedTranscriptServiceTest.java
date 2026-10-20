package com.example.server.service;

import com.example.server.dto.TranscriptSegment;
import com.example.server.editor.EditorModels.*;
import com.example.server.editor.TimedAsrProvider;
import com.example.server.editor.TimedTranscriptionService;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UnifiedTranscriptServiceTest {
    TimedTranscriptionService timed=mock(TimedTranscriptionService.class);
    TimedAsrProvider provider=mock(TimedAsrProvider.class);
    UnifiedTranscriptService service=new UnifiedTranscriptService(timed,provider,30,1);
    TranscriptDocument doc(String state){return new TranscriptDocument(4L,7L,1,"ASR_TIMED",5000,state,null,
        state.equals("COMPLETED")?List.of(new TimedCue("a",120,2340,"first"),new TimedCue("b",3100,4300,"second")):List.of());}
    @Test void completedCuesRemainAvailableWithoutProviderAndKeepRealRanges() {
        when(timed.completed(7L,1L)).thenReturn(doc("COMPLETED"));
        doThrow(new IllegalStateException("disabled")).when(provider).requireConfigured();
        assertTrue(service.supports(7L,1L));
        assertEquals(List.of(new TranscriptSegment(120,2340,"first"),new TranscriptSegment(3100,4300,"second")),service.completed(7L,1L));
        verify(provider,never()).requireConfigured();
    }
    @Test void joinsQueuedJobThenPublishesExactlyItsCompletedCues() {
        when(timed.submit(7L,1L)).thenReturn(doc("QUEUED"));
        when(timed.status(7L,1L)).thenReturn(doc("PROCESSING"),doc("COMPLETED"));
        assertEquals(List.of(new TranscriptSegment(120,2340,"first"),new TranscriptSegment(3100,4300,"second")),assertDoesNotThrow(()->service.await(7L,1L)));
        verify(timed).submit(7L,1L);verify(timed).syncText(doc("COMPLETED"),1L);
    }
    @Test void ownershipFailureCannotBecomeDisabledLegacyFallback() {
        when(timed.completed(7L,2L)).thenThrow(new SecurityException("foreign"));
        assertThrows(SecurityException.class,()->service.supports(7L,2L));verifyNoInteractions(provider);
    }
    @Test void failedAndReplacedJobsNeverReturnADifferentTranscript() {
        when(timed.submit(7L,1L)).thenReturn(doc("QUEUED"));
        when(timed.status(7L,1L)).thenReturn(doc("FAILED"));
        assertThrows(IllegalStateException.class,()->service.await(7L,1L));
        when(timed.status(7L,1L)).thenReturn(new TranscriptDocument(5L,7L,2,"ASR_TIMED",5000,"COMPLETED",null,doc("COMPLETED").cues()));
        assertThrows(IllegalStateException.class,()->service.await(7L,1L));
    }
    @Test void waitingIsBoundedAndInterruptPreservesFlagWithoutCancellingSharedJob() {
        when(timed.submit(7L,1L)).thenReturn(doc("PROCESSING"));when(timed.status(7L,1L)).thenReturn(doc("PROCESSING"));
        assertThrows(IllegalStateException.class,()->service.await(7L,1L));
        Thread.currentThread().interrupt();
        try {assertThrows(IllegalStateException.class,()->service.await(7L,1L));assertTrue(Thread.currentThread().isInterrupted());}
        finally {Thread.interrupted();}
        verify(timed,never()).interruptPending();
    }
}
