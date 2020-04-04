package com.raoulvdberge.refinedpipes.network.graph;

import com.raoulvdberge.refinedpipes.network.NetworkManager;
import com.raoulvdberge.refinedpipes.network.pipe.Destination;
import com.raoulvdberge.refinedpipes.network.pipe.DestinationType;
import com.raoulvdberge.refinedpipes.network.pipe.Pipe;
import com.raoulvdberge.refinedpipes.network.pipe.energy.EnergyPipe;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.Direction;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.energy.CapabilityEnergy;
import net.minecraftforge.fluids.capability.CapabilityFluidHandler;
import net.minecraftforge.items.CapabilityItemHandler;

import java.util.*;

public class NetworkGraphScanner {
    private final Set<Pipe> foundPipes = new HashSet<>();
    private final Set<Pipe> newPipes = new HashSet<>();
    private final Set<Pipe> removedPipes = new HashSet<>();
    private final Set<Destination> destinations = new HashSet<>();
    private final Set<Pipe> currentPipes;
    private final ResourceLocation requiredNetworkType;

    private final List<NetworkGraphScannerRequest> allRequests = new ArrayList<>();
    private final Queue<NetworkGraphScannerRequest> requests = new ArrayDeque<>();

    public NetworkGraphScanner(Set<Pipe> currentPipes, ResourceLocation requiredNetworkType) {
        this.currentPipes = currentPipes;
        this.removedPipes.addAll(currentPipes);
        this.requiredNetworkType = requiredNetworkType;
    }

    public NetworkGraphScannerResult scanAt(World world, BlockPos pos) {
        addRequest(new NetworkGraphScannerRequest(world, pos, null, null));

        NetworkGraphScannerRequest request;
        while ((request = requests.poll()) != null) {
            singleScanAt(request);
        }

        return new NetworkGraphScannerResult(
            foundPipes,
            newPipes,
            removedPipes,
            destinations,
            allRequests
        );
    }

    private void singleScanAt(NetworkGraphScannerRequest request) {
        Pipe pipe = NetworkManager.get(request.getWorld()).getPipe(request.getPos());

        if (pipe != null) {
            boolean isSameNetworkType = requiredNetworkType.equals(pipe.getNetworkType());

            if (isSameNetworkType && foundPipes.add(pipe)) {
                if (!currentPipes.contains(pipe)) {
                    newPipes.add(pipe);
                }

                removedPipes.remove(pipe);

                request.setSuccessful(true);

                for (Direction dir : Direction.values()) {
                    addRequest(new NetworkGraphScannerRequest(
                        request.getWorld(),
                        request.getPos().offset(dir),
                        dir,
                        request
                    ));
                }
            }

            // This is a workaround.
            // We can NOT have the bottom TE capability checks always run regardless of whether there was a pipe or not.
            // Otherwise we have this loop: pipe gets placed -> network gets scanned -> TEs get checked -> it might check the TE we just placed
            // -> the newly created TE can be created in immediate mode -> TE#validate is called again -> TE#remove is called again!
            // So just do this ugly check for now.
            if (!isSameNetworkType && pipe instanceof EnergyPipe) {
                ((EnergyPipe) pipe)
                    .getEnergyStorage()
                    .ifPresent(energyStorage -> destinations.add(new Destination(DestinationType.ENERGY_STORAGE, request.getPos(), request.getDirection(), pipe)));
            }
        } else if (request.getParent() != null) { // This can NOT be called on pipe positions! (causes problems with tiles getting invalidated/validates when it shouldn't)
            Pipe connectedPipe = NetworkManager.get(request.getWorld()).getPipe(request.getParent().getPos());

            // If this item handler is connected to a pipe with an attachment, then this is not a valid destination.
            if (!connectedPipe.getAttachmentManager().hasAttachment(request.getDirection())) {
                TileEntity tile = request.getWorld().getTileEntity(request.getPos());

                if (tile != null) {
                    tile.getCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, request.getDirection().getOpposite())
                        .ifPresent(itemHandler -> destinations.add(new Destination(DestinationType.ITEM_HANDLER, request.getPos(), request.getDirection(), connectedPipe)));

                    tile.getCapability(CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY, request.getDirection().getOpposite())
                        .ifPresent(fluidHandler -> destinations.add(new Destination(DestinationType.FLUID_HANDLER, request.getPos(), request.getDirection(), connectedPipe)));

                    tile.getCapability(CapabilityEnergy.ENERGY, request.getDirection().getOpposite())
                        .ifPresent(energyStorage -> destinations.add(new Destination(DestinationType.ENERGY_STORAGE, request.getPos(), request.getDirection(), connectedPipe)));
                }
            }
        }
    }

    private void addRequest(NetworkGraphScannerRequest request) {
        requests.add(request);
        allRequests.add(request);
    }
}
