package hu.u_szeged.inf.fog.simulator.application;

import java.util.ArrayList;

import hu.mta.sztaki.lpds.cloud.simulator.Timed;
import hu.mta.sztaki.lpds.cloud.simulator.iaas.PhysicalMachine;
import hu.mta.sztaki.lpds.cloud.simulator.iaas.PhysicalMachine.ResourceAllocation;
import hu.mta.sztaki.lpds.cloud.simulator.iaas.VMManager.VMManagementException;
import hu.mta.sztaki.lpds.cloud.simulator.iaas.VirtualMachine;
import hu.mta.sztaki.lpds.cloud.simulator.iaas.VirtualMachine.StateChangeException;
import hu.mta.sztaki.lpds.cloud.simulator.iaas.resourcemodel.ConsumptionEventAdapter;
import hu.mta.sztaki.lpds.cloud.simulator.iaas.resourcemodel.ResourceConsumption;
import hu.mta.sztaki.lpds.cloud.simulator.io.NetworkNode.NetworkException;
import hu.mta.sztaki.lpds.cloud.simulator.io.StorageObject;
import hu.u_szeged.inf.fog.simulator.application.strategy.ApplicationStrategy;
import hu.u_szeged.inf.fog.simulator.iot.Device;
import hu.u_szeged.inf.fog.simulator.physical.ComputingAppliance;
import hu.u_szeged.inf.fog.simulator.provider.Instance;
import hu.u_szeged.inf.fog.simulator.util.TimelineGenerator.TimelineEntry;

public class Application extends Timed {
	
	public static long lastAction;

    public static ArrayList < Application > allApplications = new ArrayList < > ();

    public static long totalProcessedSize = 0;
    
    public ArrayList < AppVm > utilisedVMs;

    public ComputingAppliance computingAppliance;

    public String name;

    protected long freq;

    public ArrayList < Device > deviceList;

    long tasksize;

    public boolean serviceable;

    double instructions;

    ApplicationStrategy applicationStrategy;

    public long receivedData;

    public long processedData;

    public Instance instance;

    public int incomingData;
    
    private int taskInProgress;
    
    public ArrayList < TimelineEntry > timelineEntries = new ArrayList < TimelineEntry > ();
    
    public static long  totalTimeOnNetwork = 0;
    
    public static long totalBytesOnNetwork = 0;

    public Application(String name, long freq, long tasksize, double instructions, boolean serviceable, ApplicationStrategy applicationStrategy, Instance instance) {
        Application.allApplications.add(this);
        this.deviceList = new ArrayList < > ();
        this.utilisedVMs = new ArrayList < > ();
        this.name = name;
        this.instance = instance;
        this.freq = freq;
        this.tasksize = tasksize;
        this.serviceable = serviceable;
        this.instructions = instructions;
        this.applicationStrategy = applicationStrategy;
        this.applicationStrategy.application = this;
    }

    public void setComputingAppliance(ComputingAppliance ca) {
        this.computingAppliance = ca;
        this.computingAppliance.iaas.repositories.get(0).registerObject(this.instance.va);
    }

    public void subscribeApplication() {
        subscribe(this.freq);
        
        
        if(this.computingAppliance.gateway.vm.getState().equals(VirtualMachine.State.SHUTDOWN)) {
        	 try {
				ResourceAllocation ra = this.computingAppliance.gateway.pm.allocateResources(ComputingAppliance.gatewayArc,
				         false, PhysicalMachine.defaultAllocLen);
				this.computingAppliance.gateway.vm.switchOn(ra, null);
				this.computingAppliance.gateway.restartCounter++;
				this.computingAppliance.gateway.runningPeriod = Timed.getFireCount();
				System.out.println(this.computingAppliance.name + " gateway is turned on at: " + Timed.getFireCount());
			} catch (VMManagementException | NetworkException e) {
				e.printStackTrace();
			}
    	}
    	
    }

    AppVm VmSearch() {
        for (AppVm appVm: this.utilisedVMs) {
            if (!appVm.isWorking && appVm.vm.getState().equals(VirtualMachine.State.RUNNING)) {
                return appVm;
            }
        }
        return null;
    }

    private boolean createVm() {
        try {
            if (this.turnonVM() == false) {
                for (PhysicalMachine pm: this.computingAppliance.iaas.machines) {
                    if (pm.isReHostableRequest(this.instance.arc)) {
                        VirtualMachine vm = pm.requestVM(this.instance.va, this.instance.arc,
                            this.computingAppliance.iaas.repositories.get(0), 1)[0];
                        if (vm != null) {
                            AppVm appVm = new AppVm(vm);
                            appVm.pm = pm;
                            this.utilisedVMs.add(appVm);
                            System.out.println("VM-"+ appVm.id + " is requested at: " + Timed.getFireCount());
                            return true;
                        }
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return false;
    }

    private boolean turnonVM() {
        for (AppVm appVm: this.utilisedVMs) {
            if (appVm.vm.getState().equals(VirtualMachine.State.SHUTDOWN) &&
                appVm.pm.isReHostableRequest(this.instance.arc)) {
                try {
                    ResourceAllocation ra = appVm.pm.allocateResources(this.instance.arc,
                        false, PhysicalMachine.defaultAllocLen);
                    appVm.vm.switchOn(ra, null);
                    appVm.restartCounter++;
                    appVm.runningPeriod = Timed.getFireCount();
                    System.out.println("VM-"+ appVm.id + " turned on at: " + Timed.getFireCount());
                    return true;
                } catch ( NetworkException | VMManagementException e) {
                    e.printStackTrace();
                }
            }
        }
        return false;
    }

    private boolean checkDeviceState() {
        for (Device d: this.deviceList) {
            if (d.isSubscribed()) {
                return false;
            }
        }
        return true;
    }

    private void turnoffVM() {
        for (AppVm appVm: this.utilisedVMs) {
            if (appVm.vm.getState().equals(VirtualMachine.State.RUNNING) &&
                appVm.isWorking == false) {
                try {
                    appVm.vm.switchoff(false);
                    System.out.println(name + " VM-" + appVm.id + " is turned off at: " + Timed.getFireCount());
                } catch (StateChangeException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    private void countVmRunningTime() {
		for(AppVm appVm : this.utilisedVMs) {
			if(appVm.vm.getState().equals(VirtualMachine.State.RUNNING)) {
				appVm.workTime += (Timed.getFireCount() -appVm.runningPeriod);
				appVm.runningPeriod = Timed.getFireCount();
			}
		}
	}
    
    void saveStorageObject(long dataToBeSaved) {
        StorageObject so = new StorageObject(this.name, dataToBeSaved, false);
        if (!this.computingAppliance.iaas.repositories.get(0).registerObject(so)) {
            System.err.println("Error in Application.java: Processed data cannot be saved.");
            System.exit(0);
        }
    }

    
    @Override
    public void tick(long fires) {
        long unprocessedData = (this.receivedData - this.processedData);

        if (unprocessedData > 0) {
            long alreadyProcessedData = 0;
            while (unprocessedData != alreadyProcessedData) {
                long allocatedData = Math.min(unprocessedData - alreadyProcessedData, this.tasksize);
                
                final AppVm appVm = this.VmSearch();
                if (appVm == null) {
                	double ratio = (double) unprocessedData / this.tasksize;
                	System.out.print(name + " has " + unprocessedData + " bytes left, " + this.computingAppliance.getLoadOfResource() +" load (%)," + " unprocessed data / tasksize ratio: " + ratio + ". Decision: ");
                	if(Double.compare(ratio, this.applicationStrategy.activationRatio) > 0) {
                		long dataForTransfer = ((long) ((unprocessedData-alreadyProcessedData)/this.applicationStrategy.transferDivider));
            			System.out.print("data is ready to be transferred: " + dataForTransfer + " ");  
                		this.applicationStrategy.findApplication(dataForTransfer);
                	}
                    this.createVm();
                    break;
                }

                final double noi = allocatedData == this.tasksize ? this.instructions :
                    (this.instructions * allocatedData / this.tasksize);
                alreadyProcessedData += allocatedData;
                this.processedData += allocatedData;
                Application.totalProcessedSize += allocatedData;
                appVm.isWorking = true;
                this.taskInProgress++;
                try {
                    appVm.vm.newComputeTask(noi, ResourceConsumption.unlimitedProcessing, new ConsumptionEventAdapter() {
                    	final long taskStartTime = Timed.getFireCount();
                    	final long allocatedDataTemp = allocatedData;
                    	final double noiTemp = noi;
                    	 
                        @Override
                        public void conComplete() {
                        	saveStorageObject(allocatedData);
                            appVm.isWorking = false;
                            appVm.taskCounter++;
                            taskInProgress--;
                            Application.lastAction = Timed.getFireCount();
                            timelineEntries.add(new TimelineEntry(taskStartTime, Timed.getFireCount(), Integer.toString(appVm.id)));
                            System.out.println(name + " VM-" + appVm.id + " started at: " + taskStartTime + " finished at: " + Timed.getFireCount() +
                            		" bytes: " + allocatedDataTemp + " took: " + (Timed.getFireCount()-taskStartTime) + " instructions: " + noiTemp );
                        }
                    });
                } catch (NetworkException e) {
                    e.printStackTrace();
                }
                

            }
        }
        this.countVmRunningTime();
        this.turnoffVM();

        if (this.incomingData == 0 && this.taskInProgress == 0 && this.processedData == this.receivedData &&
            this.checkDeviceState()) {
            unsubscribe();
             
            try {
            	if(this.computingAppliance.gateway.vm.getState().equals(VirtualMachine.State.RUNNING)) {
            		this.computingAppliance.gateway.pm = this.computingAppliance.gateway.vm.getResourceAllocation().getHost();
            		this.computingAppliance.gateway.vm.switchoff(false);
            		this.computingAppliance.gateway.workTime += (Timed.getFireCount() - this.computingAppliance.gateway.runningPeriod);
            		timelineEntries.add(new TimelineEntry(this.computingAppliance.gateway.runningPeriod, Timed.getFireCount(), this.computingAppliance.name + "-gateway"));
            		System.out.println(this.computingAppliance.name + " gateway is turned off at: " + Timed.getFireCount() + " " );
            	}
			} catch (StateChangeException e) {
				e.printStackTrace();
			}
			
        }
    }
}