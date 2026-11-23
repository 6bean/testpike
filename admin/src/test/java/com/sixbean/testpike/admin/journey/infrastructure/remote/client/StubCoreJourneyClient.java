package com.sixbean.testpike.admin.journey.infrastructure.remote.client;

import com.sixbean.testpike.admin.journey.api.dto.AddJourneySegmentRequest;
import com.sixbean.testpike.admin.journey.api.dto.CreateJourneyRequest;
import com.sixbean.testpike.admin.journey.api.dto.JourneyPageView;
import com.sixbean.testpike.admin.journey.api.dto.JourneyRunView;
import com.sixbean.testpike.admin.journey.api.dto.JourneySegmentView;
import com.sixbean.testpike.admin.journey.api.dto.JourneyView;

/**
 * Integration-boundary stand-in for {@link CoreJourneyClient}, used only by the admin
 * bridge contract test to observe what the controller forwards. The real admin to core
 * chain is exercised by the L3 bridge smoke at segment close (never mocked there).
 */
public class StubCoreJourneyClient implements CoreJourneyClient {

    private long createdJourneyId;
    private CreateJourneyRequest lastCreateRequest;
    private long lastSegmentJourneyId;
    private AddJourneySegmentRequest lastSegmentRequest;
    private JourneySegmentView returnedSegment;
    private long lastJourneyReadId;
    private JourneyView returnedJourney;
    private JourneyPageView returnedPage;
    private long lastPageProgramId;
    private int lastPageNumber;
    private int lastPageSizeNumber;
    private String lastPageKeyword;
    private long lastStartRunJourneyId;
    private long lastGetRunId;
    private long lastSignOffRunId;
    private JourneyRunView returnedRun;

    public void createdJourneyId(long id) { this.createdJourneyId = id; }
    public CreateJourneyRequest lastCreateRequest() { return lastCreateRequest; }
    public void returnedSegment(JourneySegmentView view) { this.returnedSegment = view; }
    public long lastSegmentJourneyId() { return lastSegmentJourneyId; }
    public AddJourneySegmentRequest lastSegmentRequest() { return lastSegmentRequest; }
    public void returnedJourney(JourneyView view) { this.returnedJourney = view; }
    public long lastJourneyReadId() { return lastJourneyReadId; }
    public void returnedPage(JourneyPageView view) { this.returnedPage = view; }
    public long lastPageProgramId() { return lastPageProgramId; }
    public int lastPage() { return lastPageNumber; }
    public int lastPageSize() { return lastPageSizeNumber; }
    public String lastPageKeyword() { return lastPageKeyword; }
    public void returnedRun(JourneyRunView view) { this.returnedRun = view; }
    public long lastStartRunJourneyId() { return lastStartRunJourneyId; }
    public long lastGetRunId() { return lastGetRunId; }
    public long lastSignOffRunId() { return lastSignOffRunId; }

    @Override
    public long createJourney(CreateJourneyRequest request) {
        this.lastCreateRequest = request;
        return createdJourneyId;
    }

    @Override
    public JourneySegmentView addSegment(long journeyId, AddJourneySegmentRequest request) {
        this.lastSegmentJourneyId = journeyId;
        this.lastSegmentRequest = request;
        return returnedSegment;
    }

    @Override
    public JourneyView getJourney(long journeyId) {
        this.lastJourneyReadId = journeyId;
        return returnedJourney;
    }

    @Override
    public JourneyPageView pageJourneys(long programId, int page, int pageSize, String keyword) {
        this.lastPageProgramId = programId;
        this.lastPageNumber = page;
        this.lastPageSizeNumber = pageSize;
        this.lastPageKeyword = keyword;
        return returnedPage;
    }

    @Override
    public JourneyRunView startRun(long journeyId) {
        this.lastStartRunJourneyId = journeyId;
        return returnedRun;
    }

    @Override
    public JourneyRunView getRun(long runId) {
        this.lastGetRunId = runId;
        return returnedRun;
    }

    @Override
    public JourneyRunView signOff(long runId) {
        this.lastSignOffRunId = runId;
        return returnedRun;
    }
}
