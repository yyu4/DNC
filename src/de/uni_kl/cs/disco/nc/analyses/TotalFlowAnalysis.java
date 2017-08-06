/*
 * This file is part of the Disco Deterministic Network Calculator v2.4.0beta1 "Chimera".
 *
 * Copyright (C) 2005 - 2007 Frank A. Zdarsky
 * Copyright (C) 2008 - 2010 Andreas Kiefer
 * Copyright (C) 2011 - 2017 Steffen Bondorf
 * Copyright (C) 2017 The DiscoDNC contributors
 *
 * Distributed Computer Systems (DISCO) Lab
 * University of Kaiserslautern, Germany
 *
 * http://disco.cs.uni-kl.de/index.php/projects/disco-dnc
 *
 *
 * The Disco Deterministic Network Calculator (DiscoDNC) is free software;
 * you can redistribute it and/or modify it under the terms of the 
 * GNU Lesser General Public License as published by the Free Software Foundation; 
 * either version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 */

package de.uni_kl.cs.disco.nc.analyses;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import de.uni_kl.cs.disco.misc.Pair;
import de.uni_kl.cs.disco.nc.Analysis;
import de.uni_kl.cs.disco.nc.AnalysisConfig;
import de.uni_kl.cs.disco.nc.ArrivalBound;
import de.uni_kl.cs.disco.nc.AnalysisConfig.MuxDiscipline;
import de.uni_kl.cs.disco.nc.operations.BacklogBound;
import de.uni_kl.cs.disco.nc.operations.DelayBound;
import de.uni_kl.cs.disco.network.Flow;
import de.uni_kl.cs.disco.network.Network;
import de.uni_kl.cs.disco.network.Path;
import de.uni_kl.cs.disco.network.Server;
import de.uni_kl.cs.disco.numbers.Num;
import de.uni_kl.cs.disco.numbers.NumFactory;
import de.uni_kl.cs.disco.numbers.NumUtils;
import de.uni_kl.cs.disco.curves.ArrivalCurve;
import de.uni_kl.cs.disco.curves.ServiceCurve;

public class TotalFlowAnalysis extends Analysis {
    @SuppressWarnings("unused")
    private TotalFlowAnalysis() {
    }

    public TotalFlowAnalysis(Network network) {
        super(network);
        super.result = new TotalFlowResults();
    }

    public TotalFlowAnalysis(Network network, AnalysisConfig configuration) {
        super(network, configuration);
        super.result = new TotalFlowResults();
    }

    public void performAnalysis(Flow flow_of_interest) throws Exception {
        performAnalysis(flow_of_interest, flow_of_interest.getPath());
    }

    public void performAnalysis(Flow flow_of_interest, Path path) throws Exception {
        Num delay_bound = NumFactory.createZero();
        Num backlog_bound = NumFactory.createZero();

        for (Server server : path.getServers()) {
            Pair<Num> min_D_B = deriveBoundsAtServer(server);

            delay_bound = NumUtils.add(delay_bound, min_D_B.getFirst());
            backlog_bound = NumUtils.max(backlog_bound, min_D_B.getSecond());
        }

        ((TotalFlowResults) result).setDelayBound(delay_bound);
        ((TotalFlowResults) result).setBacklogBound(backlog_bound);
    }

    public Pair<Num> deriveBoundsAtServer(Server server) throws Exception {
        // Here's the difference to SFA:
        // TFA needs the arrival bound of all flows at the server, including the flow of interest.
        Set<ArrivalCurve> alphas_server = ArrivalBound.computeArrivalBounds(network, configuration, server);
        // Although the TFA has a flow of interest, DO NOT call
        // computeArrivalBounds( Network network, AnalysisConfig configuration, Server server, Set<Flow> flows_to_bound, Flow flow_of_interest ).

        Set<Num> delay_bounds_server = new HashSet<Num>();
        Set<Num> backlog_bounds_server = new HashSet<Num>();

        Num delay_bound_s__min = NumFactory.getPositiveInfinity();
        Num backlog_bound_s__min = NumFactory.getPositiveInfinity();
        for (ArrivalCurve alpha_candidate : alphas_server) {
            // According to the call of computeOutputBound there's no left-over service curve calculation
            ServiceCurve beta_server = server.getServiceCurve();

            Num backlog_bound_server_alpha = BacklogBound.derive(alpha_candidate, beta_server);
            backlog_bounds_server.add(backlog_bound_server_alpha);

            if (backlog_bound_server_alpha.leq(backlog_bound_s__min)) {
                backlog_bound_s__min = backlog_bound_server_alpha;
            }

            // Is this a single flow, i.e., does fifo per micro flow hold?
            boolean fifo_per_micro_flow = false;
            if (network.getFlows(server).size() == 1) {
                fifo_per_micro_flow = true;
            }

            Num delay_bound_server_alpha;
            if (configuration.multiplexingDiscipline() == MuxDiscipline.GLOBAL_FIFO
                    || (configuration.multiplexingDiscipline() == MuxDiscipline.SERVER_LOCAL && server.multiplexingDiscipline() == AnalysisConfig.Multiplexing.FIFO)
                    || fifo_per_micro_flow) {
                delay_bound_server_alpha = DelayBound.deriveFIFO(alpha_candidate, beta_server);
            } else {
                delay_bound_server_alpha = DelayBound.deriveARB(alpha_candidate, beta_server);
            }
            delay_bounds_server.add(delay_bound_server_alpha);

            if (delay_bound_server_alpha.leq(delay_bound_s__min)) {
                delay_bound_s__min = delay_bound_server_alpha;
            }
        }
        ((TotalFlowResults) result).map__server__alphas.put(server, alphas_server);
        ((TotalFlowResults) result).map__server__D_server.put(server, delay_bounds_server);
        ((TotalFlowResults) result).map__server__B_server.put(server, backlog_bounds_server);

        return new Pair<Num>(delay_bound_s__min, backlog_bound_s__min);
    }

    public Map<Server, Set<Num>> getServerDelayBoundMap() {
        return ((TotalFlowResults) result).map__server__D_server;
    }

    public String getServerDelayBoundMapString() {
        return ((TotalFlowResults) result).getServerDelayBoundMapString();
    }

    public Map<Server, Set<Num>> getServerBacklogBoundMap() {
        return ((TotalFlowResults) result).map__server__B_server;
    }

    public String getServerBacklogBoundMapString() {
        return ((TotalFlowResults) result).getServerBacklogBoundMapString();
    }
}