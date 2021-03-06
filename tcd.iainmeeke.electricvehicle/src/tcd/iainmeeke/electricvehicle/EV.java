package tcd.iainmeeke.electricvehicle;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;

import javax.measure.Measure;
import javax.measure.unit.SI;

import org.flexiblepower.context.FlexiblePowerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import aQute.bnd.annotation.component.Activate;
import aQute.bnd.annotation.component.Component;
import aQute.bnd.annotation.component.Deactivate;
import aQute.bnd.annotation.metatype.Configurable;
import aQute.bnd.annotation.metatype.Meta;
import net.powermatcher.api.AgentEndpoint;
import net.powermatcher.api.data.Bid;
import net.powermatcher.api.data.MarketBasis;
import net.powermatcher.api.data.PointBidBuilder;
import net.powermatcher.api.data.Price;
import net.powermatcher.api.messages.BidUpdate;
import net.powermatcher.api.messages.EVUpdate;
import net.powermatcher.api.messages.PriceUpdate;
import net.powermatcher.api.monitoring.ObservableAgent;
import net.powermatcher.api.monitoring.events.EVUpdateEvent;
import net.powermatcher.core.BaseAgentEndpoint;

/**
 * {@link PVPanelAgent} is a implementation of a {@link BaseAgentEndpoint}. It represents a dummy electriv vehicle.
 * {@link PVPanelAgent} creates a {@link PointBid} with random {@link PricePoint}s at a set interval. It does nothing
 * with the returned {@link Price}.
 * 
 * @author FAN
 * @version 2.0
 */
@Component(
        designateFactory = EV.Config.class,
        immediate = true,
        provide = { ObservableAgent.class, AgentEndpoint.class })
public class EV extends BaseAgentEndpoint implements AgentEndpoint {

    private static final Logger LOGGER = LoggerFactory.getLogger(EV.class);
    /**
     * This lock makes sure that {@link FpaiAgent#createBid(net.powermatcher.api.AgentEndpoint.Status)} and
     * {@link FpaiAgent#handlePriceUpdate(Price)} cannot be executed in parallel.
     */
    private final Object lock = new Object();
    private Config config;

    public static interface Config {
        @Meta.AD(deflt = "EV", description = "The unique identifier of the agent")
        String agentId();

        @Meta.AD(
                deflt = "concentrator",
                description = "The agent identifier of the parent matcher to which this agent should be connected")
        public String desiredParentId();

        @Meta.AD(deflt = "5", description = "Number of seconds between bid updates")
        long bidUpdateRate();

        @Meta.AD(
                deflt = "LEAF",
                description = "What model of EV is it? LEAF, VOLT or TESLA?",
                optionLabels = { "LEAF", "VOLT", "TESLA" })
        String evModel();

        @Meta.AD(deflt = "16:00", description = "Lower Bound of the random time the car may get home at")
        String timeHomeLower();

        @Meta.AD(deflt = "19:00", description = "Upper Bound of the random time the car may get home at")
        String timeHomeUpper();

        @Meta.AD(deflt = "07:00", description = "Lower Bound of the random time the car needs to be charged by")
        String chargeByLower();

        @Meta.AD(deflt = "10:00", description = "Upper Bound of the random time the car needs to be charge by")
        String chargeByUpper();
    }

    /**
     * A delayed result-bearing action that can be cancelled.
     */
    private ScheduledFuture<?> scheduledFuture;

    private EVSimulation ev;

    /**
     * OSGi calls this method to activate a managed service.
     * 
     * @param properties
     *            the configuration properties
     */
    @Activate
    public void activate(Map<String, Object> properties) {
        config = Configurable.createConfigurable(Config.class, properties);
        init(config.agentId(), config.desiredParentId());
        LOGGER.info("Agent [{}], activated", config.agentId());
    }

    /**
     * OSGi calls this method to deactivate a managed service.
     */
    @Override
    @Deactivate
    public void deactivate() {
        super.deactivate();
        scheduledFuture.cancel(false);
        LOGGER.info("Agent [{}], deactivated", getAgentId());
    }

    /**
     * {@inheritDoc}
     */
    void doBidUpdate() {
        AgentEndpoint.Status currentStatus = getStatus();
        if (currentStatus.isConnected()) {
            EVUpdate update = new EVUpdate(ev.getTimeToChargeRatio(), ev.getCurrentChargeKwh(), ev.getChargingAt(),
                    ev.getArriveHomeTime(), ev.getDesiredChargeTime(), ev.getPluggedIn(), ev.isCharging(),
                    context.currentTime());
            publishEvent(new EVUpdateEvent(currentStatus.getClusterId(), getAgentId(),
                    currentStatus.getSession().getSessionId(), now(), update));
            if (ev.getPluggedIn()) { // this call also updates the EVSimulation
                synchronized (lock) {
                    MarketBasis mb = currentStatus.getMarketBasis();
                    double carChargeDesire = ev.getTimeToChargeRatio();
                    double demand = ev.getChargePower();
                    if (carChargeDesire == 1) {
                        publishBid(Bid.flatDemand(currentStatus.getMarketBasis(), demand));
                    } else {
                        publishBid(new PointBidBuilder(mb).add(mb.getMaximumPrice() / carChargeDesire, demand)
                                .add((mb.getMaximumPrice() / carChargeDesire), 0).build());
                    }
                }
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public final void handlePriceUpdate(PriceUpdate priceUpdate) {
        super.handlePriceUpdate(priceUpdate);
        BidUpdate lastBidUpdate = getLastBidUpdate();
        double demandForCurrentPrice = 0; //set to zero initially and then overwrite it if we need to
        if (lastBidUpdate == null) {
            LOGGER.info("Ignoring price update while no bid has been sent");
        } else if (lastBidUpdate.getBidNumber() != priceUpdate.getBidNumber()) {
            LOGGER.info("Ignoring price update on old bid (lastBid={} priceUpdate={})", lastBidUpdate.getBidNumber(),
                    priceUpdate.getBidNumber());
        } else if (ev.getPluggedIn()) { // only handle the price if the car is plugged in
            demandForCurrentPrice = getLastBidUpdate().getBid().getDemandAt(priceUpdate.getPrice()); // the demand at that price
            synchronized (lock) {
                ev.setCharging(demandForCurrentPrice); // set the car to charge at this power
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setContext(FlexiblePowerContext context) {
        super.setContext(context);
        try {
            setEVSim();
        } catch (ParseException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
        scheduledFuture = context.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                doBidUpdate();
            }
        }, Measure.valueOf(0, SI.SECOND), Measure.valueOf(config.bidUpdateRate(), SI.SECOND));
    }

    /**
     * parses the dates entered by the user and initialises the electric vehicle
     * 
     * @throws ParseException
     */
    private void setEVSim() throws ParseException {
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm");
        Long currentTimeMillis = context.currentTimeMillis();
        Calendar currentDate = Calendar.getInstance();
        currentDate.setTimeInMillis(currentTimeMillis);

        Date dateTimeHomeLower = sdf.parse(config.timeHomeLower());
        Date dateTimeHomeUpper = sdf.parse(config.timeHomeUpper());
        Date dateChargeByLower = sdf.parse(config.chargeByLower());
        Date dateChargeByUpper = sdf.parse(config.chargeByUpper());

        Calendar calendarTimeHomeLower = GregorianCalendar.getInstance();
        Calendar calendarTimeHomeUpper = GregorianCalendar.getInstance();
        Calendar calendarChargeByLower = GregorianCalendar.getInstance();
        Calendar calendarChargeByUpper = GregorianCalendar.getInstance();

        calendarTimeHomeLower.setTime(dateTimeHomeLower);
        calendarTimeHomeUpper.setTime(dateTimeHomeUpper);
        calendarChargeByLower.setTime(dateChargeByLower);
        calendarChargeByUpper.setTime(dateChargeByUpper);

        calendarTimeHomeLower.set(Calendar.YEAR, currentDate.get(Calendar.YEAR));
        calendarTimeHomeLower.set(Calendar.MONTH, currentDate.get(Calendar.MONTH));
        calendarTimeHomeLower.set(Calendar.DATE, currentDate.get(Calendar.DATE));

        calendarTimeHomeUpper.set(Calendar.YEAR, currentDate.get(Calendar.YEAR));
        calendarTimeHomeUpper.set(Calendar.MONTH, currentDate.get(Calendar.MONTH));
        calendarTimeHomeUpper.set(Calendar.DATE, currentDate.get(Calendar.DATE));

        calendarChargeByLower.set(Calendar.YEAR, currentDate.get(Calendar.YEAR));
        calendarChargeByLower.set(Calendar.MONTH, currentDate.get(Calendar.MONTH));
        calendarChargeByLower.set(Calendar.DATE, currentDate.get(Calendar.DATE) + 1);

        calendarChargeByUpper.set(Calendar.YEAR, currentDate.get(Calendar.YEAR));
        calendarChargeByUpper.set(Calendar.MONTH, currentDate.get(Calendar.MONTH));
        calendarChargeByUpper.set(Calendar.DATE, currentDate.get(Calendar.DATE) + 1);

        ev = new EVSimulation(EVType.valueOf(config.evModel()), super.context, calendarTimeHomeLower,
                calendarTimeHomeUpper, calendarChargeByLower, calendarChargeByUpper);

    }
}
