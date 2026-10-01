package com.ikyam.vendornex.controller;

import com.ikyam.vendornex.db.Row;
import com.ikyam.vendornex.repository.DashboardQueries;
import com.ikyam.vendornex.repository.ScorecardQueries;
import com.ikyam.vendornex.security.CurrentUser;
import com.ikyam.vendornex.security.Role;
import com.ikyam.vendornex.web.Roles;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
public class DashboardController {

    private final DashboardQueries dashboardQueries;
    private final ScorecardQueries scorecardQueries;

    public DashboardController(DashboardQueries dashboardQueries, ScorecardQueries scorecardQueries) {
        this.dashboardQueries = dashboardQueries;
        this.scorecardQueries = scorecardQueries;
    }

    @GetMapping("/api/dashboard")
    @Roles(Role.ADMIN)
    public Object admin(CurrentUser u) {
        UUID c = u.company();
        Row k = dashboardQueries.adminKpis(c);
        k.put("recentPurchaseOrders", dashboardQueries.recentPurchaseOrders(c, 6));
        k.put("onboardingPipeline", dashboardQueries.onboardingPipeline(c, 8));
        k.put("connection", dashboardQueries.companyConnection(c));
        k.put("lastSyncAt", dashboardQueries.lastSyncAt(c));
        return k;
    }

    @GetMapping("/api/vendor/dashboard")
    @Roles(Role.VENDOR)
    public Object vendor(CurrentUser u) {
        Row k = dashboardQueries.vendorKpis(u.vendor());
        k.put("recentPurchaseOrders", dashboardQueries.vendorRecentPurchaseOrders(u.vendor(), u.company(), 6));
        List<Row> s = scorecardQueries.scores(u.company(), u.vendor());
        if (!s.isEmpty()) {
            Row sc = s.get(0);
            k.put("scorecard", new Row().with("otif", sc.get("otif")).with("quality", sc.get("quality")).with("priceVariance", sc.get("priceVariance")));
        }
        return k;
    }
}
