import arc.files.*;
import mindustry.*;
import mindustry.campaign.shared.*;
import mindustry.campaign.shared.SharedCampaignState.*;
import mindustry.campaign.shared.io.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.*;

import java.nio.file.*;

import static org.junit.jupiter.api.Assertions.*;

/** Crash-window regression: coordinator restart must not leave a PREPARING research transaction orphaned forever. */
@Tag("shared-campaign-research-recovery")
public class SharedCampaignResearchReconciliationTests{
    @BeforeAll static void bootstrap(){ ApplicationTests.launchApplication(true); }

    @TempDir Path temp;

    @Test
    @Timeout(30)
    void authorityRestartAbortsPreparingResearchAndRecordsAllPossibleLiveReservations(){
        Fi campaign = new Fi(temp.resolve("campaign").toFile());
        Fi mods = new Fi(temp.resolve("mods").toFile());
        mods.mkdirs();

        SharedCampaignService created = new SharedCampaignService(Vars.game(), mods);
        SharedCampaignCreationOptions options = new SharedCampaignCreationOptions();
        options.displayName = "Research restart reconciliation";
        options.ownerId = "owner";
        options.ownerDisplayName = "Owner";
        options.primaryPlanetName = "serpulo";
        created.createLocal(campaign, options, "127.0.0.1", 0, 0);
        created.close();

        long now = System.currentTimeMillis();
        try(SharedCampaignStore store = new SharedCampaignStore(campaign)){
            store.open();
            store.transact("fixture", "test:inject-preparing-research", state -> {
                SectorState sectorA = new SectorState();
                sectorA.planetName = "serpulo";
                sectorA.sectorName = "groundZero";
                sectorA.hasBase = true;
                state.sectors.put("serpulo:groundZero", sectorA);

                SectorState sectorB = new SectorState();
                sectorB.planetName = "serpulo";
                sectorB.sectorName = "frozenForest";
                sectorB.hasBase = true;
                state.sectors.put("serpulo:frozenForest", sectorB);

                ResearchTransaction tx = new ResearchTransaction();
                tx.transactionId = "research-crash-window";
                tx.actorId = "owner";
                tx.contentName = "router";
                tx.status = ResearchTransactionStatus.preparing;
                tx.createdAt = now;
                tx.updatedAt = now;

                ResearchDebit debitA = new ResearchDebit();
                debitA.sectorName = "serpulo:groundZero";
                debitA.actionId = "action-a";
                debitA.items.put("copper", 10);
                tx.debits.put(debitA.sectorName, debitA);

                ResearchDebit debitB = new ResearchDebit();
                debitB.sectorName = "serpulo:frozenForest";
                debitB.actionId = "action-b";
                debitB.items.put("lead", 5);
                tx.debits.put(debitB.sectorName, debitB);

                state.researchTransactions.put(tx.transactionId, tx);
            });
        }

        SharedCampaignService reopened = new SharedCampaignService(Vars.game(), mods);
        try{
            SharedCampaignState state = reopened.openLocal(campaign, "owner", "127.0.0.1", 0, 0);
            ResearchTransaction recovered = state.researchTransactions.get("research-crash-window");
            assertNotNull(recovered);
            assertEquals(ResearchTransactionStatus.aborted, recovered.status,
                "PREPARING research has no durable commit decision and must fail closed on authority restart");
            assertTrue(recovered.preparedActions.contains("action-a"),
                "restart recovery must conservatively remember every live debit target because PREPARE may have completed before the crash");
            assertTrue(recovered.preparedActions.contains("action-b"));
        }finally{
            reopened.close();
        }
    }
}
