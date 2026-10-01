package com.ikyam.vendornex.controller;

import com.ikyam.vendornex.db.Row;
import com.ikyam.vendornex.http.ApiException;
import com.ikyam.vendornex.repository.ScorecardQueries;
import com.ikyam.vendornex.security.CurrentUser;
import com.ikyam.vendornex.security.Role;
import com.ikyam.vendornex.web.Roles;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Vendor scorecard, derived from portal + B1 receipt data:
 * <ul>
 *   <li><b>OTIF</b> — % of due PO lines received in full on or before the line delivery date;</li>
 *   <li><b>Quality</b> — accepted ÷ (accepted + rejected) quantity on posted GRPOs
 *       (the natural hook for InspectPro inward-inspection results);</li>
 *   <li><b>Price variance</b> — average % of PO unit price over the item's B1 average cost (negative = cheaper).</li>
 * </ul>
 * Trend uses monthly snapshots (vendor_score_snapshots) plus the live current figure.
 */
@RestController
public class ScorecardController {

    private final ScorecardQueries scorecardQueries;
    private static ScorecardQueries staticScorecardQueries;

    public ScorecardController(ScorecardQueries scorecardQueries) {
        this.scorecardQueries = scorecardQueries;
        staticScorecardQueries = scorecardQueries;
    }

    @GetMapping("/api/scorecard")
    @Roles(Role.ADMIN)
    public Object scorecard(CurrentUser u) {
        return scorecardQueries.scores(u.company(), null);
    }

    public static List<Row> scores(UUID c, UUID vendorId) {
        return staticScorecardQueries != null ? staticScorecardQueries.scores(c, vendorId) : List.of();
    }

    @GetMapping("/api/vendor/scorecard")
    @Roles(Role.VENDOR)
    public Object mine(CurrentUser u) {
        List<Row> rows = scorecardQueries.scores(u.company(), u.vendor());
        if (rows.isEmpty()) throw ApiException.notFound("Scorecard");
        Row r = rows.get(0);
        r.remove("spend"); // vendors see their performance, not the buyer's spend analytics
        return r;
    }
}
